/* pdclock.c - PocketPd logical-clock watchdog (rewritten 0.17.159).
 *
 * 0.17.158's version was BROKEN and rushed every [delay]/[pipe] (user
 * report, immediately after install): it self-gated on "clock did not
 * advance since the previous visit", ticking when frozen. The gate cannot
 * distinguish "audio thread dead" from "audio thread between blocks": the
 * visit cadence (~1.45 ms) equals the audio block duration, so with slight
 * phase offset the watchdog ticked BETWEEN the render thread's blocks while
 * audio was running - logical time then advanced at ~1.5-2x real rate and
 * every delay/pipe fired early. Lesson recorded in CHANGELOG 0.17.159.
 *
 * This rewrite removes all clock-advance heuristics. Two hard rules:
 *
 *  1. EXPLICIT GATING: the watchdog runs only while the audio engine is
 *     stopped - PdClock.start()/stop() are driven by PdEngine.stopAudio/
 *     startAudio (and one initial start at PdEngine.init, audio-off being
 *     the process's initial state). While the render thread exists the
 *     watchdog thread idles in a short poll and never touches pd.
 *
 *  2. REAL-TIME CATCH-UP, not fixed cadence: while active, the watchdog
 *     advances logical time to match wall-clock elapsed time, exactly like
 *     desktop pd's no-audio polling scheduler (m_sched.c compares
 *     sys_getrealtime against logical time and ticks to catch up). Each
 *     visit computes lag = (real elapsed) - (logical elapsed) against an
 *     anchor; lag >= one block triggers a burst of floor(lag/block)
 *     sched_tick() calls (capped), then re-anchors. If the clock is instead
 *     AHEAD of real time by more than a block (guard against gating
 *     mistakes - audio running while the watchdog thinks it is off), the
 *     anchor is dropped so no catch-up is owed. The watchdog can therefore
 *     never run the clock faster than wall time, even if both gates fail.
 *
 * pd's control objects ([timer]/[delay]/[metro]/[pipe]/[line]) all measure
 * LOGICAL time (pd_systime), which advances only inside sched_tick(). On
 * libpd that fires ONLY from the audio render callback
 * (sched_audio_callbackfn); desktop pd additionally keeps the clock alive
 * without audio (m_callbackscheduler watchdog + real-time polling
 * scheduler). libpd never runs m_mainloop, so with the dsp key off, a
 * failed audio init or during the process's lazy boot window the clock
 * froze: [timer] reported 0 forever, [delay]/[metro] never fired (the
 * "timer triggers but does not count time" report, 0.17.157 era).
 *
 * The tick runs under sys_lock - the same mutex the render path takes
 * (sched_audio_callbackfn) - so even a transient overlap with the audio
 * thread can never interleave inside pd.
 *
 * pd symbols resolve via dlopen/dlsym (the libpdtweaks.so pattern); all
 * five are exported by the bundled libpd.so (llvm-nm verified):
 * sched_tick, sys_lock, sys_unlock, clock_getlogicaltime, sys_getrealtime.
 * Semantics from the pd 0.51-4 sources this runtime is built from
 * (slibs/pd55-ref/m_sched_0514.c): sched_tick is no-arg and advances
 * pd_systime by (st_schedblocksize/st_dacsr)*TIMEUNITPERSECOND, then runs
 * dsp_tick() (a no-op while pd's dsp switch is off - PdEngine.stopAudio
 * turns the switch off so the signal graph is not re-run here); TIMEUNITPERMSEC
 * = 14112 pd time units per millisecond; sys_getrealtime returns real
 * seconds since pd boot. st_dacsr defaults to DEFDACSAMPLERATE at instance
 * init (m_class.c), so sched_tick's division is safe once libpd_init has
 * run - which is guaranteed before start: PdBase's static initializer
 * calls initialize() (libpd_init) on the class's first use, and
 * PdEngine.init touches PdBase before starting this watchdog.
 */

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "PocketPd.Clock"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* m_sched.c: pd time units per millisecond */
#define TIMEUNITPERMSEC (32.0 * 441.0)
/* burst cap: blocks per visit (~370 ms logical at 44.1 kHz/64) - after a
long suspension the catch-up continues over the following visits */
#define MAX_BURST 256

static void   (*p_sys_lock)(void);
static void   (*p_sys_unlock)(void);
static void   (*p_sched_tick)(void);
static double (*p_clock_getlogicaltime)(void);
static double (*p_sys_getrealtime)(void);

static pthread_t g_thread;
static atomic_int g_alive = 0;    /* thread created and looping */
static atomic_int g_started = 0;  /* startNative ran (idempotence) */
static int g_resolved = 0;

static void *watchdog_loop(void *arg)
{
    (void)arg;
    /* anchor: real time and logical time start in step */
    double ref_logical_ms = -1.0;
    double ref_real_ms = -1.0;
    long bursts = 0;
    while (atomic_load(&g_alive))
    {
        usleep(2500);
        p_sys_lock();
        double logical_ms =
            p_clock_getlogicaltime() / TIMEUNITPERMSEC;
        double real_ms = p_sys_getrealtime() * 1000.0;
        if (ref_logical_ms < 0.0)
        {
            /* first visit after start: anchor, tick nothing */
            ref_logical_ms = logical_ms;
            ref_real_ms = real_ms;
        }
        else
        {
            double lag = (real_ms - ref_real_ms)
                - (logical_ms - ref_logical_ms);
            /* one block of logical time per tick, derived from the clock
            itself: tick once and measure - the advance is exactly
            (st_schedblocksize/st_dacsr)*1000 ms */
            if (lag >= 1.0)
            {
                p_sched_tick();
                double advanced =
                    (p_clock_getlogicaltime() / TIMEUNITPERMSEC) - logical_ms;
                int extra = 0;
                if (advanced >= 0.01)      /* sane block: plan the rest */
                {
                    extra = (int)(lag / advanced) - 1;
                    if (extra > MAX_BURST - 1) extra = MAX_BURST - 1;
                }
                for (int i = 0; i < extra; i++) p_sched_tick();
                bursts++;
                if (bursts == 1)
                    LOGI("clock watchdog: audio off, advancing pd logical "
                         "clock to wall time");
                /* re-anchor after catching up */
                ref_logical_ms =
                    p_clock_getlogicaltime() / TIMEUNITPERMSEC;
                ref_real_ms = p_sys_getrealtime() * 1000.0;
            }
            else if (lag <= -1.0)
            {
                /* clock ahead of wall time by more than a block: gating
                mistake (audio running while we think it is off) - drop
                the anchor so no catch-up is ever owed */
                ref_logical_ms = logical_ms;
                ref_real_ms = real_ms;
            }
        }
        p_sys_unlock();
    }
    if (bursts > 0)
        LOGI("clock watchdog stopped after %ld catch-up bursts", bursts);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_pocketpd_app_runtime_PdClock_startNative(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    if (atomic_load(&g_started)) return JNI_TRUE;
    if (!g_resolved)
    {
        void *h = dlopen("libpd.so", RTLD_NOW);
        if (!h)
        {
            LOGI("dlopen libpd.so failed: %s", dlerror());
            return JNI_FALSE;
        }
        p_sys_lock = (void (*)(void))dlsym(h, "sys_lock");
        p_sys_unlock = (void (*)(void))dlsym(h, "sys_unlock");
        p_sched_tick = (void (*)(void))dlsym(h, "sched_tick");
        p_clock_getlogicaltime =
            (double (*)(void))dlsym(h, "clock_getlogicaltime");
        p_sys_getrealtime = (double (*)(void))dlsym(h, "sys_getrealtime");
        if (!p_sys_lock || !p_sys_unlock || !p_sched_tick ||
            !p_clock_getlogicaltime || !p_sys_getrealtime)
        {
            LOGI("dlsym failed: sys_lock=%p sys_unlock=%p sched_tick=%p "
                 "clock_getlogicaltime=%p sys_getrealtime=%p",
                 (void *)p_sys_lock, (void *)p_sys_unlock,
                 (void *)p_sched_tick, (void *)p_clock_getlogicaltime,
                 (void *)p_sys_getrealtime);
            return JNI_FALSE;
        }
        g_resolved = 1;
    }
    atomic_store(&g_alive, 1);
    if (pthread_create(&g_thread, NULL, watchdog_loop, NULL) != 0)
    {
        atomic_store(&g_alive, 0);
        return JNI_FALSE;
    }
    atomic_store(&g_started, 1);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_pocketpd_app_runtime_PdClock_stopNative(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    if (!atomic_load(&g_started)) return;
    atomic_store(&g_alive, 0);
    pthread_join(g_thread, NULL);
    atomic_store(&g_started, 0);
}

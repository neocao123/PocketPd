"""Fake OpenAI-compatible chat-completions server for end-to-end testing.

Logs each request body to fake_llm_requests.log, replies with a small
canned .pd patch. Port 8123, reachable from the emulator via adb reverse.
"""
import json
import http.server

CANNED = """#N canvas 0 0 450 300 10;
#X obj 50 50 osc~ 330;
#X obj 50 120 gain 0.8;
#X obj 50 190 dac~ 1 2;
#X text 40 20 AI test patch;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 2 1;
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n).decode("utf-8")
        hdr = {k: v for k, v in self.headers.items()
               if k.lower() in ("x-api-key", "authorization", "anthropic-version")}
        with open("fake_llm_requests.log", "a", encoding="utf-8") as f:
            f.write(self.path + " " + json.dumps(hdr) + " " + body + "\n----\n")
        if self.path not in ("/v1/chat/completions", "/v1/messages"):
            self.send_response(404)
            self.end_headers()
            return
        anthropic = self.path == "/v1/messages"
        content = CANNED
        if "FENCE" in body:  # wrap in markdown fences like real models do
            content = "Here is your patch:\n```pd\n" + CANNED + "```\nHope it helps!"
        if "SLOW90" in body:  # long window for blink/capture tests
            import time
            time.sleep(90)
        elif "SLOW" in body:  # delay HEADERS so the client sits waiting to read
            import time
            time.sleep(20)
        if anthropic:
            reply = json.dumps({
                "content": [{"type": "text", "text": content}]
            }).encode("utf-8")
        else:
            reply = json.dumps({
                "choices": [{"message": {"role": "assistant", "content": content}}]
            }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)

    def log_message(self, *a):
        pass


http.server.HTTPServer(("127.0.0.1", 8123), Handler).serve_forever()

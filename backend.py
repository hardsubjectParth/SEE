# python -m uvicorn backend:app --host 0.0.0.0 --port 8000 
import base64
import time

import requests

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="SEE Vision Server")

OLLAMA_URL = "http://127.0.0.1:11434"
MODEL = "qwen2.5vl:3b"


class AnalyzeRequest(BaseModel):
    image: str
    prompt: str


@app.get("/")
def root():
    return {"status": "ok", "service": "SEE Vision Server", "model": MODEL}


@app.get("/health")
def health():
    try:
        response = requests.get(f"{OLLAMA_URL}/api/tags", timeout=5)
        response.raise_for_status()
        data = response.json()
        models = data.get("models", [])
        model_names = [m.get("name", "") for m in models]
        installed = MODEL in model_names

        return {
            "fastapi": "ok",
            "ollama": "ok",
            "model": MODEL,
            "model_installed": installed,
        }
    except Exception as e:
        return {
            "fastapi": "ok",
            "ollama": "error",
            "model": MODEL,
            "model_installed": False,
            "error": str(e),
        }


@app.post("/analyze")
def analyze(request: AnalyzeRequest):
    start = time.time()
    try:
        if not request.image:
            raise HTTPException(status_code=400, detail="Image is empty")

        image_data = request.image
        if "," in image_data:
            image_data = image_data.split(",", 1)[1]

        try:
            image_bytes = base64.b64decode(image_data, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid base64 image")

        if not image_bytes:
            raise HTTPException(status_code=400, detail="Decoded image is empty")

        print(f"Received image: {len(image_bytes)} bytes")

        payload = {
            "model": MODEL,
            "stream": False,
            # Keep the model resident so back-to-back requests don't pay
            # the load cost again - this is one of the biggest latency wins.
            "keep_alive": "30m",
            "messages": [
                {
                    "role": "user",
                    "content": request.prompt,
                    "images": [image_data],
                }
            ],
            "options": {
                "temperature": 0.1,
                "repeat_penalty": 1.1,
                # Short answers are both faster to generate and easier to
                # act on for a "what's in front of me" use case.
                "num_predict": 60,
                "num_ctx": 1024,
            },
        }

        response = requests.post(f"{OLLAMA_URL}/api/chat", json=payload, timeout=45)

        if not response.ok:
            print("Ollama error:", response.status_code, response.text)
            raise HTTPException(
                status_code=502,
                detail=f"Ollama returned HTTP {response.status_code}: {response.text}",
            )

        data = response.json()
        message = data.get("message", {})
        answer = message.get("content", "").strip()

        if not answer:
            answer = "I could not determine what is in the image."

        elapsed = time.time() - start
        print(f"Qwen response ({elapsed:.2f}s): {answer}")

        return {"answer": answer, "model": MODEL, "seconds": round(elapsed, 2)}

    except HTTPException:
        raise
    except requests.exceptions.ConnectionError:
        raise HTTPException(
            status_code=503,
            detail="Could not connect to Ollama. Make sure `ollama serve` is running.",
        )
    except requests.exceptions.Timeout:
        raise HTTPException(
            status_code=504, detail="Ollama timed out while processing the image."
        )
    except Exception as e:
        print("Vision server error:", repr(e))
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn

    # host="0.0.0.0" is required so your PHONE (not just the AVD) can
    # reach this server over the LAN. 127.0.0.1 only accepts local traffic.
    uvicorn.run(app, host="0.0.0.0", port=8000)

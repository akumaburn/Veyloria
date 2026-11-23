FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime
RUN apt-get update && apt-get install -y --no-install-recommends git libsndfile1 && rm -rf /var/lib/apt/lists/*
# Pin the inference source: the model repository is only a weight distribution.
ARG MING_COMMIT=c88c023ea6ccc08fd1e7a1d289cb6246c21deff1
RUN git clone https://github.com/inclusionAI/Ming-3.0-tts.git /opt/ming && cd /opt/ming && git checkout "$MING_COMMIT" && sed '/^torch==/d; /^torchaudio==/d' requirements.txt > /tmp/ming-requirements.txt && pip install --no-cache-dir -r /tmp/ming-requirements.txt
RUN pip install --no-cache-dir fastapi==0.115.12 'uvicorn[standard]==0.34.2'
ENV PYTHONPATH=/opt/ming HF_HOME=/models/huggingface PYTHONUNBUFFERED=1
WORKDIR /app
COPY services/tts/*.py ./
EXPOSE 9797
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9797", "--workers", "1", "--ws-max-size", "131072"]

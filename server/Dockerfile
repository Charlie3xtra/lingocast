FROM python:3.12-slim

WORKDIR /app
COPY lingocast_server.py /app/lingocast_server.py

ENV LINGOCAST_PACKS_DIR=/data/packs \
    LINGOCAST_HOST=0.0.0.0 \
    LINGOCAST_PORT=8765

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=3)"

CMD ["python3", "/app/lingocast_server.py"]

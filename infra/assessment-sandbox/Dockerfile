FROM python:3.13-alpine
WORKDIR /app
COPY broker.py /app/broker.py
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8081
CMD ["python", "/app/broker.py"]

"""gunicorn 配置（宝塔「Python 项目」gunicorn 模式会自动加载本文件）。

FastAPI 是 ASGI 应用，必须使用 UvicornWorker；gunicorn 默认的 sync/gthread
是 WSGI worker，会报 `FastAPI.__call__() missing 1 required positional argument: 'send'`。

定时采集在进程内运行，worker 数固定为 1，避免多进程重复调度。
"""
worker_class = "uvicorn.workers.UvicornWorker"
workers = 1

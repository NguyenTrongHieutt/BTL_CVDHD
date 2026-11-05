"""Cấu hình đọc từ biến môi trường (Consistency Conventions: mọi bí mật lấy từ env).

`GEMINI_API_KEY` cố ý KHÔNG có ở đây: SDK google-genai tự đọc biến này, nên code
của dịch vụ không bao giờ cầm giá trị khóa.
"""

from functools import lru_cache

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_EMBEDDING_MODEL = "intfloat/multilingual-e5-small"
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"


class Settings(BaseSettings):
    # hide_input_in_errors: lỗi cấu hình không được in giá trị bí mật ra log.
    model_config = SettingsConfigDict(extra="ignore", hide_input_in_errors=True)

    # Mật khẩu của role `ai_service` đặt trong DATABASE_URL lúc chạy, không có mặc định
    # trong code. Thiếu mật khẩu thì libpq tự đọc PGPASSWORD hoặc ~/.pgpass.
    database_url: SecretStr = SecretStr("postgresql://ai_service@localhost:5432/banhang")
    jwt_secret: SecretStr
    internal_service_key: SecretStr | None = None

    backend_url: str = "http://localhost:8080"
    gemini_model: str = DEFAULT_GEMINI_MODEL
    embedding_model: str = DEFAULT_EMBEDDING_MODEL

    sync_enabled: bool = False
    sync_interval_seconds: int = Field(default=60, ge=5, le=3600)

    log_level: str = "INFO"

    @field_validator("jwt_secret")
    @classmethod
    def _jwt_secret_length(cls, value: SecretStr) -> SecretStr:
        # AD-9: chuỗi UTF-8 từ 32 byte trở lên, dùng nguyên văn ở cả hai phía.
        if len(value.get_secret_value().encode("utf-8")) < 32:
            raise ValueError("JWT_SECRET phải dài ít nhất 32 byte UTF-8 (AD-9)")
        return value

    @field_validator("backend_url")
    @classmethod
    def _strip_trailing_slash(cls, value: str) -> str:
        return value.rstrip("/")

    @model_validator(mode="after")
    def _sync_needs_service_key(self) -> "Settings":
        if self.sync_enabled and self.internal_service_key is None:
            raise ValueError("SYNC_ENABLED=true cần INTERNAL_SERVICE_KEY cho change-feed")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]  # jwt_secret đến từ env

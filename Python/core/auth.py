# ============================================
# JWT Token 解析模块
# 使用和 Java 端相同的 secret 解析 token
# 不需要查数据库，直接从 token payload 中取信息
# ============================================

from jose import jwt, JWTError
from config import settings


def parse_token(token: str) -> dict:
    """
    解析 JWT token，返回 payload 字典

    Args:
        token: Bearer token 字符串（带或不带 "Bearer " 前缀均可）

    Returns:
        payload 字典，包含 userId、role、username 等字段

    Raises:
        ValueError: token 无效或已过期
    """
    # 去掉 "Bearer " 前缀（如果有）
    if token.startswith("Bearer "):
        token = token[7:]

    if not token:
        raise ValueError("Token 为空")

    try:
        payload = jwt.decode(
            token,
            settings.JWT_SECRET,
            algorithms=[settings.JWT_ALGORITHM],
        )
        return payload
    except JWTError as e:
        raise ValueError(f"Token 无效或已过期: {e}")


def get_user_id_from_token(token: str) -> int:
    """
    从 token 中获取用户ID

    返回的 userId 对应数据库 users 表的 id，
    其他表的 user_id 外键也指向这个 id
    """
    payload = parse_token(token)
    user_id = payload.get("userId")
    if user_id is None:
        raise ValueError("Token 中缺少 userId 字段")
    return int(user_id)


def get_role_from_token(token: str) -> str:
    """
    从 token 中获取用户角色

    返回: "customer" / "merchant" / "admin"
    """
    payload = parse_token(token)
    role = payload.get("role")
    if not role:
        raise ValueError("Token 中缺少 role 字段")
    return role

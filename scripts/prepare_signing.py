"""Restore CI signing material outside the checkout without printing secrets."""
import base64
import os
from pathlib import Path


def prepare(env):
    names = ('KEYSTORE_BASE64', 'KEY_ALIAS', 'KEY_PASSWORD', 'STORE_PASSWORD')
    if not all(env.get('DAIYU_RELEASE_' + name) for name in names):
        raise ValueError('固定签名 Secrets 不完整')
    try:
        data = base64.b64decode(env['DAIYU_RELEASE_KEYSTORE_BASE64'], validate=True)
    except ValueError:
        raise ValueError('固定签名文件编码无效') from None
    if not 0 < len(data) <= 16 * 1024 * 1024:
        raise ValueError('固定签名文件大小无效')
    path = Path(env['RUNNER_TEMP']) / 'daiyu-release.p12'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
    return path


if __name__ == '__main__':
    prepare(os.environ)

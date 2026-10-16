#!/usr/bin/env python3
"""Pinned sherpa-ncnn Android JNI preparation. Never fetches model weights.

Usage: python3 scripts/prepare-speech-runtime.py OUTPUT [--archive VERIFIED_TAR]
An optional local archive supports offline CI; it is subjected to the same complete tar hash.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import tarfile
import urllib.request

URL = 'https://github.com/k2-fsa/sherpa-ncnn/releases/download/v2.1.15/sherpa-ncnn-v2.1.15-android.tar.bz2'
SHA256 = '7fbdb6f8bb1f8ec092467ed61d9ec85c01f060d125ccf632ef9d7ec5816d4a65'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
LIBS = ('libsherpa-ncnn-jni.so', 'libncnn.so')
MAX_TAR_BYTES = 12 * 1024 * 1024


def prepare(output, local_archive=None):
    if local_archive is None:
        with urllib.request.urlopen(URL, timeout=120) as response:
            data = response.read(MAX_TAR_BYTES + 1)
    else:
        with Path(local_archive).open('rb') as source:
            data = source.read(MAX_TAR_BYTES + 1)
    if len(data) > MAX_TAR_BYTES or hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError('Speech runtime SHA-256 mismatch')
    # Never extractall: only these eight regular files are allowed into APK input.
    expected = {f'./jniLibs/{abi}/{name}': (abi, name) for abi in ABIS for name in LIBS}
    payloads = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as archive:
        for member in archive.getmembers():
            if member.name not in expected:
                continue
            if member.name in payloads or not member.isfile() or member.size > 30 * 1024 * 1024:
                raise RuntimeError('Invalid or duplicate runtime member')
            with archive.extractfile(member) as source:
                content = source.read(member.size + 1)
            if len(content) != member.size or not content.startswith(b'\x7fELF'):
                raise RuntimeError('Invalid ELF runtime member')
            payloads[member.name] = content
    if set(payloads) != set(expected):
        raise RuntimeError('Missing runtime member')
    # Do not publish any library until the entire tar and all eight members were validated.
    for member_name, (abi, name) in expected.items():
        target = output / abi / name
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix('.tmp')
        try:
            with tmp.open('wb') as stream:
                stream.write(payloads[member_name])
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(tmp, target)
        finally:
            tmp.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    prepare(args.output, args.archive)

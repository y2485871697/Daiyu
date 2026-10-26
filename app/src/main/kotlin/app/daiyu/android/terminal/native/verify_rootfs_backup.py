"""Host-only DRFS1 round trips and refusal cases; never runs guest code."""
import hashlib
import os
from pathlib import Path
import stat
import struct
import subprocess
import tempfile


def verify(helper, output):
    passed = []
    with tempfile.TemporaryDirectory(prefix='rootfs-backup-') as name:
        base = Path(name)
        source = base / 'source'; source.mkdir(mode=0o700)
        (source / 'tmp').mkdir(); (source / 'tmp').chmod(0o1777)
        (source / 'data').write_bytes(b'rootfs\0payload\xff')
        (source / 'data').chmod(0o751)
        os.utime(source / 'data', ns=(1234567890123456789, 1234567890123456789))
        os.link(source / 'data', source / 'hard')
        (source / 'relative').symlink_to('data')
        (source / 'absolute').symlink_to('/usr/bin/sh')
        (source / 'dangling').symlink_to('not-present')
        (source / '.daiyu-rootfs-install').write_text('old transaction')
        os.setxattr(source / 'data', 'user.daiyu-backup', b'attribute')

        def command(action, root, payload=None, extra=(), identity=None):
            value = root.stat() if identity is None else identity
            return subprocess.run([str(helper), '--rootfs-' + action, str(root), str(value.st_dev),
                                   str(value.st_ino), *map(str, extra)], input=payload,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)

        packed = command('pack', source)
        assert packed.returncode == 0, packed.stderr
        stream = packed.stdout
        passed.append('host pack completes without running guest code')
        restored = base / 'restored'; restored.mkdir(mode=0o700)
        ids = (os.getuid(), os.getgid(), os.getuid(), os.getgid())
        result = command('unpack', restored, stream, ids)
        assert result.returncode == 0 and result.stdout == b'DRFS1 OK\n', result.stderr
        assert (restored / 'data').read_bytes() == (source / 'data').read_bytes()
        assert stat.S_IMODE((restored / 'data').stat().st_mode) == 0o751
        assert stat.S_IMODE((restored / 'tmp').stat().st_mode) == 0o1777
        assert (restored / 'hard').stat().st_ino == (restored / 'data').stat().st_ino
        assert (restored / 'data').stat().st_mtime_ns == 1234567890123456789
        assert os.getxattr(restored / 'data', 'user.daiyu-backup') == b'attribute'
        for link in ('relative', 'absolute', 'dangling'):
            assert os.readlink(restored / link) == os.readlink(source / link)
        assert not (restored / '.daiyu-rootfs-install').exists()
        passed.extend(['content and executable bits', 'sticky directory', 'hardlink identity',
                       'mtime nanoseconds', 'user xattrs', 'relative absolute and dangling links',
                       'old installation marker excluded'])

        # A minimal stream can target parser boundaries without allocating large fixtures.
        meta = struct.pack('>IIIQII', 0o700, os.getuid(), os.getgid(), 0, 0, 0)
        def text(value):
            return struct.pack('>I', len(value)) + value
        records = {
            'truncated': stream[:-1],
            'trailing': stream + b'not EOF',
            'bad magic': b'BAD!!' + stream[5:],
            'traversal': b'DRFS1' + meta + b'F' + text(b'..') + meta + struct.pack('>Q', 0) + b'EZ',
            'absolute name': b'DRFS1' + meta + b'F' + text(b'/escape') + meta + struct.pack('>Q', 0) + b'EZ',
            'old marker injection': b'DRFS1' + meta + b'F' + text(b'.daiyu-rootfs-install') + meta + struct.pack('>Q', 0) + b'EZ',
            'special record': b'DRFS1' + meta + b'S',
            'hardlink escape': b'DRFS1' + meta + b'H' + text(b'h') + text(b'../data') + b'EZ',
            'duplicate path': b'DRFS1' + meta + (b'F' + text(b'f') + meta + struct.pack('>Q', 0)) * 2 + b'EZ',
            'missing hardlink target': b'DRFS1' + meta + b'H' + text(b'h') + text(b'absent') + b'EZ',
        }
        for label, payload in records.items():
            root = base / ('reject-' + str(len(passed))); root.mkdir(mode=0o700)
            result = command('unpack', root, payload, ids)
            assert result.returncode != 0 and result.stdout != b'DRFS1 OK\n', label
            assert not (base / 'escape').exists()
            cleanup = command('remove', root)
            assert cleanup.returncode == 0 and cleanup.stdout == b'DRFS1 OK\n', cleanup.stderr
            assert not list(root.iterdir())
            passed.append(label + ' refused and partial staging cleaned')
        not_empty = command('unpack', restored, stream, ids)
        assert not_empty.returncode != 0 and (restored / 'data').read_bytes() == (source / 'data').read_bytes()
        passed.append('existing destination never overwritten')
        mismatch = command('pack', source, identity=restored.stat())
        assert mismatch.returncode != 0 and not mismatch.stdout
        passed.append('identity mismatch refused before output')
        os.mkfifo(source / 'fifo')
        result = command('pack', source)
        assert result.returncode != 0 and not result.stdout.endswith(b'EZ')
        (source / 'fifo').unlink()
        passed.append('FIFO refused without blocking')
        readonly = base / 'readonly'; readonly.mkdir(); (readonly / 'zero').mkdir()
        (readonly / 'zero' / 'data').write_text('cleanup'); (readonly / 'zero').chmod(0)
        readonly.chmod(0)
        result = command('remove', readonly)
        assert result.returncode == 0 and not list(readonly.iterdir()), result.stderr
        passed.append('cleanup handles zero-permission directories')
        linked = base / 'linked'; linked.symlink_to(source, target_is_directory=True)
        result = command('pack', linked)
        assert result.returncode != 0 and not result.stdout
        passed.append('symlink root refused')
    return {'passed': len(passed), 'tests': passed,
            'header_sha256': hashlib.sha256(Path(__file__).with_name('daiyu_rootfs_backup.h').read_bytes()).hexdigest()}

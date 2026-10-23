"""Backup / rollback of plugins + database + settings."""
from __future__ import annotations

import shutil
from datetime import datetime, timezone
from pathlib import Path


def _stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")


class BackupService:
    def __init__(self, plugins_dir: Path, backup_dir: Path, data_dir: Path) -> None:
        self.plugins_dir = plugins_dir
        self.backup_dir = backup_dir
        self.data_dir = data_dir
        self.backup_dir.mkdir(parents=True, exist_ok=True)

    def create(self, label: str = "manual") -> Path:
        name = f"backup-{_stamp()}-{label}"
        dest = self.backup_dir / name
        dest.mkdir(parents=True)
        if self.plugins_dir.exists():
            shutil.copytree(self.plugins_dir, dest / "plugins", ignore=shutil.ignore_patterns("_template"))
        db_file = self.data_dir / "bot.db"
        if db_file.exists():
            shutil.copy2(db_file, dest / "bot.db")
        (dest / "meta.txt").write_text(f"created={datetime.now(timezone.utc).isoformat()}\nlabel={label}\n")
        return dest

    def list(self) -> list[dict]:
        items: list[dict] = []
        if not self.backup_dir.exists():
            return items
        for child in sorted(self.backup_dir.iterdir(), reverse=True):
            if not child.is_dir():
                continue
            size = sum(f.stat().st_size for f in child.rglob("*") if f.is_file())
            stat = child.stat()
            items.append({
                "name": child.name,
                "path": str(child),
                "size_bytes": size,
                "created_at": datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc),
            })
        return items

    def rollback(self, name: str) -> Path:
        """Restore plugins from a backup. Returns backup path.

        NOTE: database is NOT auto-overwritten (audit safety) — bot.db from
        the backup is copied next to data/ as bot.db.restored-<stamp> so an
        admin can inspect it. Only plugins/ are rolled back.
        """
        src = (self.backup_dir / name).resolve()
        if self.backup_dir.resolve() not in src.parents or not src.is_dir():
            raise ValueError("backup not found")
        plugins_src = src / "plugins"
        if plugins_src.is_dir():
            tmp = self.plugins_dir.parent / f"plugins.bak-{_stamp()}"
            if self.plugins_dir.exists():
                shutil.move(str(self.plugins_dir), str(tmp))
            shutil.copytree(plugins_src, self.plugins_dir)
        db_src = src / "bot.db"
        if db_src.exists():
            shutil.copy2(db_src, self.data_dir / f"bot.db.restored-{_stamp()}")
        return src

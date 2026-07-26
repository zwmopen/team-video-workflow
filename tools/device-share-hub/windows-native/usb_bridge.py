import argparse
import asyncio
import base64
import os
import pathlib
import plistlib
import posixpath
import sys
import time


def encoded(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def emit(*parts: object) -> None:
    print("\t".join(str(part) for part in parts), flush=True)


async def connected_devices() -> list[tuple[str, str, str, bool]]:
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.usbmux import list_devices

    result: list[tuple[str, str, str, bool]] = []
    for device in await list_devices():
        if not device.is_usb:
            continue
        name = "iPhone"
        model = "iPhone"
        ready = False
        lockdown = None
        try:
            lockdown = await create_using_usbmux(serial=device.serial, autopair=False)
            name = getattr(lockdown, "display_name", "") or name
            model = getattr(lockdown, "product_type", "") or getattr(lockdown, "hardware_model", "") or model
            ready = True
        except Exception:
            pass
        finally:
            if lockdown is not None:
                await lockdown.close()
        result.append((device.serial, name, model, ready))
    return result


async def list_command() -> int:
    try:
        devices = await connected_devices()
    except ImportError:
        emit("BRIDGE", "missing")
        return 0
    except Exception as error:
        emit("BRIDGE", "error", encoded(str(error)))
        return 0
    emit("BRIDGE", "ready")
    for serial, name, model, ready in devices:
        emit("DEVICE", encoded(serial), encoded(name), encoded(model), "1" if ready else "0")
    return 0


def local_stats(sources: list[pathlib.Path]) -> tuple[int, int]:
    count = 0
    total = 0
    for source in sources:
        if source.is_file():
            count += 1
            total += source.stat().st_size
            continue
        for path in source.rglob("*"):
            if path.is_file():
                count += 1
                total += path.stat().st_size
    return count, total


async def find_album_bundle(lockdown) -> str:
    from pymobiledevice3.services.installation_proxy import InstallationProxyService

    apps = await InstallationProxyService(lockdown).get_apps(application_type="Any")
    candidates = sorted(
        bundle for bundle in apps
        if bundle == "com.zwm.album" or bundle.startswith("com.zwm.album.")
    )
    if not candidates:
        raise RuntimeError("手机上没有安装“相册”")
    return candidates[0]


async def selected_documents_root(afc, bundle_id: str) -> str:
    for preference in (
        f"Library/Preferences/{bundle_id}.plist",
        "Library/Preferences/com.zwm.album.plist",
    ):
        try:
            raw = await afc.get_file_contents(preference)
            relative = plistlib.loads(raw).get("album.managedFolderRelativePath.v1", "")
            parts = pathlib.PurePosixPath(str(relative).replace("\\", "/")).parts
            if relative and ".." not in parts:
                return posixpath.join("Documents", str(relative).strip("/"))
        except Exception:
            pass
    return "Documents"


async def send_command(serial: str, raw_sources: list[str]) -> int:
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.services.house_arrest import HouseArrestService

    sources = [pathlib.Path(value).resolve() for value in raw_sources]
    if not sources or any(not source.exists() for source in sources):
        raise RuntimeError("本地文件不存在")
    names = [source.name for source in sources]
    if len(set(name.casefold() for name in names)) != len(names):
        raise RuntimeError("同一批文件中存在重名项目")
    expected_count, expected_bytes = local_stats(sources)
    lockdown = await create_using_usbmux(serial=serial)
    try:
        bundle_id = await find_album_bundle(lockdown)
        async with await HouseArrestService.create(
            lockdown, bundle_id, documents_only=False
        ) as afc:
            root = await selected_documents_root(afc, bundle_id)
            staging = posixpath.join(root, f".相册USB传送-{int(time.time())}")
            await afc.makedirs(staging)
            try:
                for source in sources:
                    target = posixpath.join(root, source.name)
                    try:
                        await afc.stat(target)
                    except Exception:
                        pass
                    else:
                        raise RuntimeError(f"手机中已存在同名项目：{source.name}")

                completed = 0

                def progress(local_path: str, _remote_path: str) -> None:
                    nonlocal completed
                    path = pathlib.Path(local_path)
                    if path.is_file():
                        completed += path.stat().st_size
                        emit("PROGRESS", completed, expected_bytes)

                for source in sources:
                    await afc.push(str(source), staging, callback=progress)

                received_count = 0
                received_bytes = 0
                for source in sources:
                    staged = posixpath.join(staging, source.name)
                    if source.is_dir():
                        async for directory, _dirs, remote_names in afc.walk(staged):
                            for name in remote_names:
                                info = await afc.stat(posixpath.join(directory, name))
                                received_count += 1
                                received_bytes += int(info.get("st_size", 0))
                    else:
                        info = await afc.stat(staged)
                        received_count += 1
                        received_bytes += int(info.get("st_size", 0))
                if (received_count, received_bytes) != (expected_count, expected_bytes):
                    raise RuntimeError(
                        f"USB 核验失败：预期 {expected_count}/{expected_bytes}，"
                        f"收到 {received_count}/{received_bytes}"
                    )
                for source in sources:
                    await afc.rename(
                        posixpath.join(staging, source.name),
                        posixpath.join(root, source.name),
                    )
                await afc.rm(staging, force=True)
                emit("RESULT", received_count, received_bytes, encoded(bundle_id))
                return 0
            except Exception:
                try:
                    await afc.rm(staging, force=True)
                except Exception:
                    pass
                raise
    finally:
        await lockdown.close()


async def async_main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    send = sub.add_parser("send")
    send.add_argument("--serial", required=True)
    send.add_argument("--source", action="append", required=True)
    args = parser.parse_args()
    if args.command == "list":
        return await list_command()
    return await send_command(args.serial, args.source)


def main() -> int:
    try:
        return asyncio.run(async_main())
    except Exception as error:
        emit("ERROR", encoded(str(error)))
        return 2


if __name__ == "__main__":
    sys.exit(main())

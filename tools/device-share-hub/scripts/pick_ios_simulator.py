#!/usr/bin/env python3
"""Pick an iPhone simulator for the CI unit-test run.

Why this lives in a file instead of ``python3 -c``
--------------------------------------------------
The previous implementation embedded the picker inside a YAML block scalar::

    simulator_id=$(xcrun simctl list devices available -j | python3 -c '
    import json,sys
    ...
    ')

YAML keeps every line of a block scalar indented, so the snippet reached
CPython with leading whitespace on each line and died with
``IndentationError: unexpected indent`` (verified locally on CPython:
``python3 -c '<indented import>'`` -> exit 1, empty stdout).  The command
substitution therefore printed nothing, ``simulator_id`` stayed empty and the
workflow silently fell back to ``build-for-testing`` -- the iOS unit tests were
compiled but **never executed**, which made a green job meaningless.

Keeping the logic in a real file makes it importable and unit-testable.

Output contract (exactly one line on stdout)
--------------------------------------------
``device:<udid>``                          reuse an existing iPhone simulator
``create:<runtime-id>\\t<device-type>``      nothing installed yet -- create one
``<empty>``                                nothing usable; caller must say so loudly
"""

from __future__ import annotations

import argparse
import json
import re
import sys

IPHONE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone"
IOS_RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS"


def load(path: str | None) -> dict:
    """Read one of the ``simctl -j`` dumps. Missing/garbled file -> empty."""
    if not path:
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            doc = json.load(handle)
    except (OSError, ValueError):
        return {}
    return doc if isinstance(doc, dict) else {}


def version_key(value: object) -> list[int]:
    return [int(part) for part in str(value or "0").split(".") if part.isdigit()]


def groups(doc: dict, key: str) -> list[dict]:
    return [item for item in doc.get(key, []) if isinstance(item, dict)]


def iphone_entries(devices_doc: dict) -> list[dict]:
    """Every iPhone-shaped entry, tolerating garbled `devices` payloads."""
    devices = devices_doc.get("devices")
    if not isinstance(devices, dict):
        return []
    return [
        device
        for runtime_devices in devices.values()
        for device in (runtime_devices if isinstance(runtime_devices, list) else [])
        if isinstance(device, dict) and str(device.get("name", "")).startswith("iPhone")
    ]


def model_key(item: dict) -> tuple[int, str]:
    """Sort by model number, not by string ("iPhone-8" must lose to "iPhone-16")."""
    identifier = str(item.get("identifier", ""))
    match = re.search(r"iPhone-(\d+)", identifier)
    return (int(match.group(1)) if match else 0, identifier)


def pick_existing(devices_doc: dict) -> str:
    """UDID of the first iPhone simulator that has no availability error.

    ``isAvailable`` is deliberately ignored: it is absent on some Xcode
    versions and its absence used to make the picker skip perfectly usable
    devices.
    """
    candidates = [
        device for device in iphone_entries(devices_doc) if not device.get("availabilityError")
    ]
    if not candidates:
        return ""
    return str(candidates[0].get("udid", "") or "")


def pick_creation(runtimes_doc: dict, devicetypes_doc: dict) -> tuple[str, str]:
    """(runtime id, device type id) for ``simctl create``, or ("", "")."""
    runtimes = [
        runtime
        for runtime in groups(runtimes_doc, "runtimes")
        if str(runtime.get("identifier", "")).startswith(IOS_RUNTIME)
        and not runtime.get("availabilityError")
    ]
    runtimes.sort(key=lambda runtime: version_key(runtime.get("version")), reverse=True)
    if not runtimes:
        return ("", "")
    runtime = runtimes[0]
    supported = [
        item
        for item in (runtime.get("supportedDeviceTypes") or [])
        if isinstance(item, dict)
        and str(item.get("identifier", "")).startswith(IPHONE_TYPE)
    ]
    if not supported:
        supported = [
            item
            for item in groups(devicetypes_doc, "devicetypes")
            if str(item.get("identifier", "")).startswith(IPHONE_TYPE)
        ]
    if not supported:
        return ("", "")
    supported.sort(key=model_key)
    return (str(runtime.get("identifier", "")), str(supported[-1].get("identifier", "")))


def diagnose(devices_doc: dict, runtimes_doc: dict, devicetypes_doc: dict) -> None:
    """Explain on stderr why nothing was picked (stdout stays on contract)."""
    iphones = iphone_entries(devices_doc)
    print(
        "no usable iPhone simulator: %d iPhone device(s) listed, %d usable"
        % (len(iphones), sum(1 for d in iphones if not d.get("availabilityError"))),
        file=sys.stderr,
    )
    for device in iphones[:10]:
        print(
            "  device %s | %s | %s"
            % (
                device.get("name"),
                device.get("udid"),
                device.get("availabilityError") or "available",
            ),
            file=sys.stderr,
        )
    for runtime in groups(runtimes_doc, "runtimes"):
        print(
            "  runtime %s | %s | %s"
            % (
                runtime.get("identifier"),
                runtime.get("version"),
                runtime.get("availabilityError") or "available",
            ),
            file=sys.stderr,
        )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--devices", help="path to `simctl list devices available -j`")
    parser.add_argument("--runtimes", help="path to `simctl list runtimes -j`")
    parser.add_argument("--devicetypes", help="path to `simctl list devicetypes -j`")
    args = parser.parse_args(argv)

    udid = pick_existing(load(args.devices))
    if udid:
        print("device:%s" % udid)
        return 0
    runtimes_doc = load(args.runtimes)
    devicetypes_doc = load(args.devicetypes)
    runtime_id, device_type = pick_creation(runtimes_doc, devicetypes_doc)
    if runtime_id and device_type:
        print("create:%s\t%s" % (runtime_id, device_type))
        return 0
    diagnose(load(args.devices), runtimes_doc, devicetypes_doc)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

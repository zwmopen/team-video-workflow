#!/usr/bin/env python3
"""Unit tests for pick_ios_simulator.py.

These exist because the picker used to be a `python3 -c` snippet buried in a
YAML block scalar and silently died with IndentationError -- which is exactly
how the iOS unit tests ended up "compiled but never run".  A picker that cannot
be executed must not be trusted, so it is now a module with tests that run in
CI (see the "Verify iOS simulator picker" step).
"""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import pick_ios_simulator as picker

IOS_RT = "com.apple.CoreSimulator.SimRuntime.iOS"
IPHONE = "com.apple.CoreSimulator.SimDeviceType.iPhone"


def _write(payload):
    handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(payload, handle)
    handle.close()
    return handle.name


def _run(devices=None, runtimes=None, devicetypes=None):
    argv = ["--devices", devices or "", "--runtimes", runtimes or "", "--devicetypes", devicetypes or ""]
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        picker.main(argv)
    return out.getvalue().strip(), err.getvalue().strip()


class PickExistingTests(unittest.TestCase):
    def test_reuses_first_usable_iphone(self):
        """A listed iPhone with no availabilityError is reused as-is."""
        devices = _write(
            {
                "devices": {
                    "com.apple.CoreSimulator.SimRuntime.iOS-18-5": [
                        {"name": "iPad Air", "udid": "PAD-1"},
                        {"name": "iPhone 16", "udid": "IPH-1"},
                        {"name": "iPhone 16 Pro", "udid": "IPH-2"},
                    ]
                }
            }
        )
        stdout, _ = _run(devices=devices)
        self.assertEqual(stdout, "device:IPH-1")

    def test_skips_devices_with_availability_error(self):
        """isAvailable is unreliable across Xcode versions; availabilityError is not."""
        devices = _write(
            {
                "devices": {
                    "com.apple.CoreSimulator.SimRuntime.iOS-18-5": [
                        {"name": "iPhone 16", "udid": "BROKEN", "availabilityError": "runtime profile not found"},
                        {"name": "iPhone 16 Pro", "udid": "GOOD"},
                    ]
                }
            }
        )
        stdout, _ = _run(devices=devices)
        self.assertEqual(stdout, "device:GOOD")

    def test_missing_isavailable_field_does_not_hide_device(self):
        """Regression: the old filter required isAvailable and skipped everything."""
        devices = _write({"devices": {"r": [{"name": "iPhone 16", "udid": "NO-FLAG"}]}})
        stdout, _ = _run(devices=devices)
        self.assertEqual(stdout, "device:NO-FLAG")


class PickCreationTests(unittest.TestCase):
    def test_creates_from_newest_runtime(self):
        """No installed device -> create one on the newest usable iOS runtime."""
        runtimes = _write(
            {
                "runtimes": [
                    {"identifier": IOS_RT + "-17-0", "version": "17.0"},
                    {"identifier": IOS_RT + "-18-5", "version": "18.5"},
                    {"identifier": "com.apple.CoreSimulator.SimRuntime.tvOS-18-5", "version": "18.5"},
                ]
            }
        )
        devicetypes = _write(
            {
                "devicetypes": [
                    {"identifier": IPHONE + "-8", "name": "iPhone 8"},
                    {"identifier": IPHONE + "-16", "name": "iPhone 16"},
                    {"identifier": "com.apple.CoreSimulator.SimDeviceType.iPad-Pro", "name": "iPad Pro"},
                ]
            }
        )
        stdout, _ = _run(devices=_write({"devices": {}}), runtimes=runtimes, devicetypes=devicetypes)
        self.assertEqual(stdout, "create:%s-18-5\t%s-16" % (IOS_RT, IPHONE))

    def test_prefers_device_types_supported_by_the_runtime(self):
        """iOS 18 cannot run an iPhone 8; the runtime's own list must win."""
        runtimes = _write(
            {
                "runtimes": [
                    {
                        "identifier": IOS_RT + "-18-5",
                        "version": "18.5",
                        "supportedDeviceTypes": [
                            {"identifier": IPHONE + "-16-Pro-Max"},
                            {"identifier": IPHONE + "-16"},
                        ],
                    }
                ]
            }
        )
        devicetypes = _write({"devicetypes": [{"identifier": IPHONE + "-8"}]})
        stdout, _ = _run(devices=_write({"devices": {}}), runtimes=runtimes, devicetypes=devicetypes)
        self.assertEqual(stdout, "create:%s-18-5\t%s-16-Pro-Max" % (IOS_RT, IPHONE))

    def test_unavailable_runtime_is_skipped(self):
        runtimes = _write(
            {
                "runtimes": [
                    {"identifier": IOS_RT + "-18-5", "version": "18.5", "availabilityError": "not downloaded"},
                    {"identifier": IOS_RT + "-17-0", "version": "17.0"},
                ]
            }
        )
        devicetypes = _write({"devicetypes": [{"identifier": IPHONE + "-16"}]})
        stdout, _ = _run(devices=_write({"devices": {}}), runtimes=runtimes, devicetypes=devicetypes)
        self.assertEqual(stdout, "create:%s-17-0\t%s-16" % (IOS_RT, IPHONE))


class NoSimulatorTests(unittest.TestCase):
    def test_empty_output_but_loud_diagnostics(self):
        """Stdout stays on contract (empty); stderr must explain why."""
        stdout, stderr = _run(devices=_write({"devices": {}}), runtimes=_write({"runtimes": []}))
        self.assertEqual(stdout, "")
        self.assertIn("no usable iPhone simulator", stderr)

    def test_garbled_input_is_tolerated(self):
        broken = _write({"devices": "not-a-list"})
        stdout, stderr = _run(devices=broken)
        self.assertEqual(stdout, "")
        self.assertTrue(stderr)

    def test_missing_files_are_tolerated(self):
        stdout, _ = _run(devices=os.path.join(tempfile.gettempdir(), "definitely-missing.json"))
        self.assertEqual(stdout, "")


if __name__ == "__main__":
    unittest.main(verbosity=2)

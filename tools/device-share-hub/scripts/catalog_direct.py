"""Optional WebRTC path for originals explicitly requested from the remote catalog."""

import asyncio
import hashlib
import json
import re
import struct
import threading
from pathlib import Path

from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription


class CatalogDirect:
    def __init__(self, bridge):
        self.bridge = bridge
        self.loop = asyncio.new_event_loop()
        self.sessions = set()
        threading.Thread(target=self.loop.run_forever, name="CatalogDirect", daemon=True).start()

    def receive_offer(self, event):
        signal = event.get("signal") or {}
        offer = signal.get("data") or {}
        if signal.get("type") != "offer" or offer.get("catalogOriginals") != 1:
            return
        session_id = event.get("sessionId", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", session_id):
            return
        self.loop.call_soon_threadsafe(self._accept, session_id, offer)

    def _accept(self, session_id, offer):
        if session_id not in self.sessions and len(self.sessions) < 8:
            self.sessions.add(session_id)
            asyncio.create_task(self._respond(session_id, offer))

    async def _respond(self, session_id, offer):
        peer = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer("stun:stun.cloudflare.com:3478")]))
        channel_closed = asyncio.Event()
        send_task = None
        route = f"/v1/p2p/sessions/{session_id}"
        try:
            token = await asyncio.to_thread(self.bridge.ensure_session)
            status, _, raw = await asyncio.to_thread(self.bridge._http, route, token=token, timeout=8)
            if status != 200:
                return
            session = json.loads(raw)["p2p"]
            if session.get("responderDeviceId") != self.bridge._device_id:
                return
            requester = session.get("initiatorDeviceId", "")
            if not self.bridge.catalog_direct_allowed(requester):
                return
            requests = asyncio.Queue(maxsize=6)

            @peer.on("datachannel")
            def on_channel(channel):
                nonlocal send_task
                if channel.label != "album-catalog-original-v1" or send_task is not None:
                    channel.close()
                    return

                @channel.on("close")
                def on_close():
                    channel_closed.set()

                @channel.on("message")
                def on_message(raw_message):
                    if isinstance(raw_message, str) and len(raw_message) <= 2048 and not requests.full():
                        requests.put_nowait(raw_message)

                send_task = asyncio.create_task(self._send_requested(channel, requests, requester))

            @peer.on("connectionstatechange")
            def on_state():
                if peer.connectionState in ("failed", "closed"):
                    channel_closed.set()

            await peer.setRemoteDescription(RTCSessionDescription(offer["sdp"], "offer"))
            await peer.setLocalDescription(await peer.createAnswer())
            answer = {"type": "answer", "sdp": peer.localDescription.sdp}
            status, _, _ = await asyncio.to_thread(self.bridge._http, route + "/signals", "POST",
                                                    json_body={"type": "answer", "data": answer},
                                                    token=token, timeout=8)
            if status in (200, 201):
                await asyncio.wait_for(channel_closed.wait(), 100)
        except Exception:
            pass  # Requests can still use the existing HTTPS path.
        finally:
            if send_task is not None:
                send_task.cancel()
            await peer.close()
            self.sessions.discard(session_id)

    async def _send_requested(self, channel, requests, requester):
        while channel.readyState != "closed":
            try:
                message = json.loads(await asyncio.wait_for(requests.get(), 100))
                if not self.bridge.catalog_direct_allowed(requester):
                    channel.close()
                    return
                request_id = message.get("id", "")
                if not re.fullmatch(r"[a-f0-9]{32}", request_id):
                    continue
                data = await asyncio.to_thread(self._read_original, message)
                if not data:
                    channel.send(json.dumps({"id": request_id, "error": True}))
                    continue
                digest = hashlib.sha256(data).hexdigest()
                channel.send(json.dumps({"id": request_id, "bytes": len(data), "sha256": digest}))
                for offset in range(0, len(data), 32 * 1024):
                    while channel.bufferedAmount > 256 * 1024 and channel.readyState == "open":
                        await asyncio.sleep(0.01)
                    channel.send(bytes.fromhex(request_id) + struct.pack(">I", offset) + data[offset:offset + 32768])
            except asyncio.TimeoutError:
                return
            except Exception:
                if channel.readyState != "open":
                    return

    def _read_original(self, request):
        from remote_catalog_bridge import catalog_source_images

        work_id, name = request.get("workId"), request.get("fileName")
        if not isinstance(work_id, str) or not isinstance(name, str):
            return None
        _, works, root = self.bridge.get_works_fn()
        work = next((entry for entry in works if entry["id"] == work_id), None)
        if work is None:
            return None
        if name.startswith("__src__:"):
            path = (self.bridge.get_source_file_fn(work_id, name)
                    if name in catalog_source_images(work) and self.bridge.get_source_file_fn else None)
        else:
            path = self.bridge.resolve_catalog_pull(root, work, name)
        if not path:
            return None
        path = Path(path)
        if path.stat().st_size > 32 * 1024 * 1024:
            return None
        return path.read_bytes()

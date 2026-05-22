from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from typing import Callable

import janus

from .types import AudioEvent, AudioEventType

logger = logging.getLogger(__name__)

EventHandler = Callable[[AudioEvent], None]


class EventBus:
    """Async event bus with sync-to-async bridge via janus.Queue.

    PortAudio drain threads call `emit_sync()` to push events.
    The async dispatcher fans them out to subscribers on the asyncio event loop.
    """

    def __init__(self, loop: asyncio.AbstractEventLoop | None = None) -> None:
        self._loop = loop
        self._queue: janus.Queue[AudioEvent] | None = None
        self._handlers: dict[AudioEventType, list[EventHandler]] = defaultdict(list)
        self._running = False
        self._dispatch_task: asyncio.Task | None = None

    async def start(self) -> None:
        if self._running:
            return
        self._loop = self._loop or asyncio.get_running_loop()
        self._queue = janus.Queue()
        self._running = True
        self._dispatch_task = asyncio.create_task(self._dispatch_loop())
        logger.info("EventBus started")

    async def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        if self._queue:
            self._queue.sync_q.put(None)  # type: ignore[arg-type]
        if self._dispatch_task:
            await self._dispatch_task
            self._dispatch_task = None
        if self._queue:
            self._queue.close()
            await self._queue.wait_closed()
            self._queue = None
        logger.info("EventBus stopped")

    def subscribe(self, event_type: AudioEventType, handler: EventHandler) -> None:
        self._handlers[event_type].append(handler)

    def unsubscribe(self, event_type: AudioEventType, handler: EventHandler) -> None:
        handlers = self._handlers.get(event_type, [])
        if handler in handlers:
            handlers.remove(handler)

    def emit_sync(self, event: AudioEvent) -> None:
        """Thread-safe emit for use from PortAudio drain threads."""
        if self._queue and self._running:
            self._queue.sync_q.put_nowait(event)

    async def emit_async(self, event: AudioEvent) -> None:
        """Async emit for use from the event loop."""
        if self._queue and self._running:
            await self._queue.async_q.put(event)

    def _resolve_event_type(self, event: AudioEvent) -> AudioEventType:
        from .types import (
            AudioDataEvent,
            ChunkReadyEvent,
            DeviceLostEvent,
            VADTransitionEvent,
        )

        type_map = {
            AudioDataEvent: AudioEventType.AUDIO_DATA,
            VADTransitionEvent: AudioEventType.VAD_TRANSITION,
            ChunkReadyEvent: AudioEventType.CHUNK_READY,
            DeviceLostEvent: AudioEventType.DEVICE_LOST,
        }
        return type_map[type(event)]

    async def _dispatch_loop(self) -> None:
        assert self._queue is not None
        while self._running:
            try:
                event = await asyncio.wait_for(
                    self._queue.async_q.get(), timeout=0.1
                )
            except asyncio.TimeoutError:
                continue

            if event is None:
                break

            event_type = self._resolve_event_type(event)
            for handler in self._handlers.get(event_type, []):
                try:
                    handler(event)
                except Exception:
                    logger.exception("Error in event handler for %s", event_type)

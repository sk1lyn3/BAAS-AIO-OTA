from core.device.virtual_scrcpy_client import VirtualScrcpyClient


class VirtualScrcpyScreenshot:
    def __init__(self, conn):
        self.cli = VirtualScrcpyClient.get_instance(conn.serial)

    def screenshot(self):
        return self.cli.screenshot()

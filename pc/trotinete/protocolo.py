"""Protocolo Bluetooth da Xiaomi Electric Scooter 5 (igual à 5 Pro/5 Max).

Tudo vive no serviço FE95. Resumo do que está documentado nos projetos
KuziaMother/SCOOTER_5_PRO e desperado0044/xiaomi-scooter-5pro-ble:

Transporte (igual em todas as características, little-endian):
    CTR    [00 00][00][canal][n.º de frames u16]   anuncia uma mensagem
    ACK    [00 00][01][estado][seq u16...]         01=pronto, 05=reenvia estes, 00=recebido
    SINGLE [00 00][02][canal][dados]               mensagem curta, sem frames
    MNG    [00 00][04][..][maxPkg][dmtu]            resposta ao handshake A4
    DATA   [seq u16 >= 1][dados]

Login (0x0010 controlo, 0x0016 canal de login):
    A4 -> MNG/MNG_ACK, depois "20 00 00", troca de chaves ECDH P-256,
    sessão = HKDF-SHA256(partilhado || ltmk), confirmação AES-CCM; 0x21 = aceite.

Propriedades (0x001a envio, 0x001b resposta, canal 0, frames de 18 bytes):
    pedidos MIoT GET/SET (siid, piid) encriptados com AES-CCM e um contador.
"""

import asyncio
import struct
import zlib

from bleak import BleakClient
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

MI_SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb"
CONTROL, LOGIN, SPEC_WRITE, SPEC_NOTIFY = 0x0010, 0x0016, 0x001A, 0x001B

CTR, ACK, SINGLE, SINGLE_ACK, MNG, MNG_ACK = range(6)
SPEC_FRAME = 18

HKDF_SALT = b"smartcfg-login-salt"
HKDF_INFO = b"smartcfg-login-info"
LOGIN_NONCE = bytes(range(0x10, 0x1C))

# Códigos de tipo MIoT (4 bits altos do campo tipo/tamanho na resposta)
BOOL, UINT8, INT8, UINT16, INT16, UINT32, INT32, UINT64, INT64, FLOAT, STRING = range(11)


class ScooterError(Exception):
    pass


def _short_id(uuid) -> int:
    return int(str(uuid).replace("-", "")[:8], 16) & 0xFFFF


def _is_ack(b: bytes) -> bool:
    return len(b) >= 4 and b[0] == 0 and b[1] == 0 and b[2] == ACK


def decode_value(type_code: int, raw: bytes):
    if type_code == STRING:
        return raw.decode("utf-8", "replace")
    if type_code == FLOAT and len(raw) == 4:
        return struct.unpack("<f", raw)[0]
    signed = type_code in (INT8, INT16, INT32, INT64)
    value = int.from_bytes(raw, "little", signed=signed)
    return bool(value) if type_code == BOOL else value


def encode_value(type_code: int, value) -> bytes:
    if type_code == STRING:
        return str(value).encode()
    if type_code == FLOAT:
        return struct.pack("<f", value)
    size = {BOOL: 1, UINT8: 1, INT8: 1, UINT16: 2, INT16: 2, UINT32: 4, INT32: 4, UINT64: 8, INT64: 8}[type_code]
    return int(value).to_bytes(size, "little", signed=type_code in (INT8, INT16, INT32, INT64))


class Scooter:
    def __init__(self, address: str):
        self.address = address
        self.client: BleakClient | None = None
        self.chars = {}
        self.queues: dict[int, asyncio.Queue] = {}
        self.dmtu = 242
        self.app_key = self.app_iv = self.dev_key = self.dev_iv = None
        self.counter = 0
        self.tid = 1

    # --- ligação e transporte ---

    async def connect(self):
        self.client = BleakClient(self.address, timeout=20.0)
        await self.client.connect()
        mi = next((s for s in self.client.services if str(s.uuid).lower() == MI_SERVICE), None)
        if mi is None:
            raise ScooterError("A trotinete não tem o serviço FE95")
        self.chars = {_short_id(c.uuid): c for c in mi.characteristics}
        spec_queue = asyncio.Queue()  # as respostas de propriedades chegam pelas duas características
        self.queues = {CONTROL: asyncio.Queue(), LOGIN: asyncio.Queue(), SPEC_WRITE: spec_queue, SPEC_NOTIFY: spec_queue}
        for sid in (CONTROL, LOGIN, SPEC_WRITE, SPEC_NOTIFY):
            await self.client.start_notify(self.chars[sid], self._on_notify)

    def _on_notify(self, sender, data: bytearray):
        sid = _short_id(sender.uuid)
        self.queues[sid].put_nowait((sid, bytes(data)))

    async def disconnect(self):
        if self.client and self.client.is_connected:
            await self.client.disconnect()

    async def _write(self, sid: int, data: bytes):
        await self.client.write_gatt_char(self.chars[sid], data, response=False)

    async def _next(self, sid: int, timeout: float):
        try:
            return await asyncio.wait_for(self.queues[sid].get(), timeout)
        except asyncio.TimeoutError:
            return None

    def _drain(self, sid: int):
        while not self.queues[sid].empty():
            self.queues[sid].get_nowait()

    async def _send_stream(self, sid: int, channel: int, payload: bytes, frame_size: int, timeout=8.0) -> bool:
        """Envia uma mensagem: CTR -> espera ACK 01 -> frames -> reenvia o que pedir (05) até ACK 00."""
        frames = [payload[i:i + frame_size] for i in range(0, len(payload), frame_size)] or [b""]

        async def send(seqs):
            for n in seqs:
                if 1 <= n <= len(frames):
                    await self._write(sid, struct.pack("<H", n) + frames[n - 1])
                    await asyncio.sleep(0.03)

        await self._write(sid, struct.pack("<HBBH", 0, CTR, channel, len(frames)))
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        sent = False
        while (left := deadline - loop.time()) > 0:
            item = await self._next(sid, left)
            if item is None:
                break
            _, b = item
            if not _is_ack(b):
                continue
            if b[3] == 0x01 and not sent:
                sent = True
                await send(range(1, len(frames) + 1))
            elif b[3] == 0x05:
                await send(struct.unpack_from(f"<{(len(b) - 4) // 2}H", b, 4))
            elif b[3] == 0x00:
                return True
        return False

    async def _recv_message(self, sid: int, timeout=8.0) -> bytes | None:
        """Recebe uma mensagem iniciada pela trotinete (SINGLE ou CTR + frames)."""
        total, frames = None, {}
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while (left := deadline - loop.time()) > 0:
            item = await self._next(sid, left)
            if item is None:
                break
            _, b = item
            if len(b) < 3:
                continue
            seq = struct.unpack_from("<H", b)[0]
            if seq == 0 and b[2] == SINGLE:
                await self._write(sid, bytes([0, 0, SINGLE_ACK, 0]))
                return b[4:]
            if seq == 0 and b[2] == CTR:
                total = struct.unpack_from("<H", b, 4)[0] if len(b) >= 6 else b[4]
                await self._write(sid, bytes([0, 0, ACK, 1]))
            elif seq != 0:
                frames[seq] = b[2:]
                if total and len(frames) >= total:
                    await self._write(sid, bytes([0, 0, ACK, 0]))
                    break
        return b"".join(frames[i] for i in sorted(frames)) if frames else None

    # --- login ---

    async def login(self, ltmk: bytes):
        self._drain(LOGIN)
        await self._write(CONTROL, bytes([0xA4]))
        item = await self._next(LOGIN, 3.0)
        if not item or len(item[1]) < 6 or item[1][2] != MNG:
            raise ScooterError("A trotinete não respondeu ao handshake (A4)")
        mng = item[1]
        self.dmtu = mng[5]
        await self._write(LOGIN, bytes([0, 0, MNG_ACK, mng[3], mng[4], mng[5]]))
        await asyncio.sleep(1.0)  # a trotinete precisa deste tempo antes do passo seguinte
        self._drain(LOGIN)

        priv = ec.generate_private_key(ec.SECP256R1())
        my_pub = priv.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)[1:]
        await self._write(CONTROL, bytes([0x20, 0x00, 0x00]))
        await asyncio.sleep(0.2)
        if not await self._send_stream(LOGIN, 3, my_pub, self.dmtu - 2):
            raise ScooterError("A trotinete não aceitou a nossa chave pública")
        dev_pub = await self._recv_message(LOGIN)
        if not dev_pub or len(dev_pub) < 64:
            raise ScooterError("Não recebi a chave pública da trotinete")
        dev_pub = dev_pub[:64]

        shared = priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(
            ec.SECP256R1(), b"\x04" + dev_pub))
        sk = HKDF(SHA256(), 64, HKDF_SALT, HKDF_INFO).derive(shared + ltmk)
        self.dev_key, self.app_key, self.dev_iv, self.app_iv = sk[0:16], sk[16:32], sk[32:36], sk[36:40]

        confirm = AESCCM(self.app_key, tag_length=4).encrypt(
            LOGIN_NONCE, struct.pack("<I", zlib.crc32(dev_pub)), None)
        self._drain(CONTROL)
        if not await self._send_stream(LOGIN, 5, confirm, self.dmtu - 2):
            raise ScooterError("A trotinete não aceitou a confirmação de login")
        item = await self._next(CONTROL, 5.0)
        if not item:
            raise ScooterError("Sem resposta ao login")
        if item[1][:1] != b"\x21":
            raise ScooterError(f"Login recusado ({item[1].hex()}): a chave não é desta trotinete?")
        self.counter = 0

    # --- propriedades MIoT ---

    def _encrypt(self, frame: bytes) -> bytes:
        nonce = self.app_iv + bytes(4) + struct.pack("<I", self.counter)
        out = struct.pack("<H", self.counter & 0xFFFF) + AESCCM(self.app_key, tag_length=4).encrypt(nonce, frame, None)
        self.counter += 1
        return out

    def _decrypt(self, payload: bytes) -> bytes:
        counter = struct.unpack_from("<H", payload)[0]
        nonce = self.dev_iv + bytes(4) + struct.pack("<I", counter)
        return AESCCM(self.dev_key, tag_length=4).decrypt(nonce, payload[2:], None)

    async def _request_once(self, frame: bytes, timeout: float) -> bytes | None:
        payload = self._encrypt(frame)
        frames = [payload[i:i + SPEC_FRAME] for i in range(0, len(payload), SPEC_FRAME)]
        self._drain(SPEC_WRITE)
        await self._write(SPEC_WRITE, struct.pack("<HBBH", 0, CTR, 0, len(frames)))

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        sent, total, parts = False, None, {}
        while (left := deadline - loop.time()) > 0:
            item = await self._next(SPEC_WRITE, left)
            if item is None:
                break
            sid, b = item
            if sid == SPEC_WRITE and _is_ack(b):
                seqs = []
                if b[3] == 0x01 and not sent:
                    sent, seqs = True, range(1, len(frames) + 1)
                elif b[3] == 0x05:
                    seqs = struct.unpack_from(f"<{(len(b) - 4) // 2}H", b, 4)
                for n in seqs:
                    if 1 <= n <= len(frames):
                        await self._write(SPEC_WRITE, struct.pack("<H", n) + frames[n - 1])
                        await asyncio.sleep(0.03)
            elif sid == SPEC_NOTIFY and len(b) >= 3 and b[0] == 0 and b[1] == 0 and b[2] == CTR:
                total = struct.unpack_from("<H", b, 4)[0] if len(b) >= 6 else b[4]
                await self._write(SPEC_NOTIFY, bytes([0, 0, ACK, 1]))
            elif sid == SPEC_NOTIFY and len(b) >= 2 and struct.unpack_from("<H", b)[0] != 0:
                parts[struct.unpack_from("<H", b)[0]] = b[2:]
                if total and len(parts) >= total:
                    await self._write(SPEC_NOTIFY, bytes([0, 0, ACK, 0]))
                    try:
                        return self._decrypt(b"".join(parts[i] for i in sorted(parts)))
                    except Exception:
                        return None
        return None

    async def _request(self, frame_for_tid, timeout: float) -> bytes | None:
        # A trotinete às vezes fica calada num pedido; uma nova tentativa (novo contador) resolve.
        for _ in range(2):
            tid, self.tid = self.tid, self.tid % 0xFFFF + 1
            reply = await self._request_once(frame_for_tid(tid), timeout)
            if reply is not None:
                return reply
        return None

    @staticmethod
    def _header(body: bytes, tid: int, op: int) -> bytes:
        return struct.pack("<HHBB", (6 + len(body)) | 0x2000, tid, op, 1) + body

    async def get(self, siid: int, piid: int, timeout=3.0):
        """Lê uma propriedade. Devolve o valor ou lança ScooterError."""
        body = struct.pack("<BHH", siid, piid, 0)
        pt = await self._request(lambda tid: self._header(body, tid, 2), timeout)
        if pt is None or len(pt) < 11:
            raise ScooterError(f"Sem resposta a ({siid},{piid})")
        status = struct.unpack_from("<H", pt, 9)[0]
        if status != 0:
            raise ScooterError(f"({siid},{piid}) recusado: estado 0x{status:04x}")
        tl = struct.unpack_from("<H", pt, 11)[0]
        return decode_value(tl >> 12, pt[13:13 + (tl & 0x0FFF)])

    async def set(self, siid: int, piid: int, type_code: int, value, timeout=8.0):
        raw = encode_value(type_code, value)
        body = struct.pack("<BHH", siid, piid, (type_code << 12) | len(raw)) + raw
        pt = await self._request(lambda tid: self._header(body, tid, 0), timeout)
        if pt is None or len(pt) < 11:
            raise ScooterError(f"Sem resposta ao alterar ({siid},{piid})")
        status = struct.unpack_from("<H", pt, 9)[0]
        if status != 0:
            raise ScooterError(f"Alteração de ({siid},{piid}) recusada: estado 0x{status:04x}")

"""Conta Xiaomi: login por QR code e pedido da chave Bluetooth (ltmk) da trotinete.

O login e a assinatura dos pedidos seguem o Xiaomi-cloud-tokens-extractor de
Piotr Machowski (MIT, https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor).
O pedido da chave (/share/askbluetoothkey) vem da documentação do projeto
KuziaMother/SCOOTER_5_PRO.

Estas APIs não são oficiais: a Xiaomi pode mudá-las sem aviso.
"""

import base64
import hashlib
import hmac
import json
import os
import random
import time

import requests
from cryptography.hazmat.decrepit.ciphers.algorithms import ARC4
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# Servidores da Xiaomi por região; em Portugal a conta costuma estar no "de".
SERVERS = ["de", "i2", "ru", "sg", "us", "cn", "tw", "in"]

# IV fixo da app Mi Home para a chave protegida por PIN (encrypt_type=1).
LTMK_PIN_IV = bytes.fromhex("7aa4c68c590d4031b980d98b41023800")


class CloudError(Exception):
    pass


def _rc4(key_b64: str, data: bytes) -> bytes:
    enc = Cipher(ARC4(base64.b64decode(key_b64)), mode=None).encryptor()
    enc.update(bytes(1024))  # a Xiaomi descarta os primeiros 1024 bytes do fluxo
    return enc.update(data)


class XiaomiCloud:
    def __init__(self):
        self.session = requests.Session()
        agent_id = "".join(chr(random.randint(65, 69)) for _ in range(13))
        rand = "".join(chr(random.randint(97, 122)) for _ in range(18))
        self.agent = f"{rand}-{agent_id} APP/com.xiaomi.mihome APPV/10.5.201"
        self.user_id = None
        self.ssecurity = None
        self.service_token = None

    # --- sessão guardada (evita repetir o QR) ---

    def save(self, path: str):
        data = {"user_id": self.user_id, "ssecurity": self.ssecurity,
                "service_token": self.service_token, "agent": self.agent}
        with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            json.dump(data, f)

    @classmethod
    def load(cls, path: str) -> "XiaomiCloud | None":
        try:
            with open(path) as f:
                data = json.load(f)
        except (OSError, ValueError):
            return None
        cloud = cls()
        cloud.user_id, cloud.ssecurity = data["user_id"], data["ssecurity"]
        cloud.service_token, cloud.agent = data["service_token"], data["agent"]
        return cloud

    # --- login por QR ---

    def start_qr_login(self) -> tuple[str, str]:
        """Pede um QR à Xiaomi. Devolve (url_da_imagem_qr, url_de_login_no_browser)."""
        r = self.session.get("https://account.xiaomi.com/longPolling/loginUrl", params={
            "_qrsize": "480",
            "qs": "%3Fsid%3Dxiaomiio%26_json%3Dtrue",
            "callback": "https://sts.api.io.mi.com/sts",
            "_hasLogo": "false",
            "sid": "xiaomiio",
            "serviceParam": "",
            "_locale": "en_GB",
            "_dc": str(int(time.time() * 1000)),
        })
        data = _json(r.text) if r.status_code == 200 else {}
        if "qr" not in data:
            raise CloudError(f"A Xiaomi não devolveu o QR (HTTP {r.status_code})")
        self._lp_url = data["lp"]
        self._lp_timeout = data["timeout"]
        return data["qr"], data["loginUrl"]

    def wait_qr_login(self):
        """Espera que o QR seja lido na Mi Home; depois obtém o serviceToken."""
        start = time.time()
        while True:
            try:
                r = self.session.get(self._lp_url, timeout=10)
            except requests.exceptions.Timeout:
                if time.time() - start > self._lp_timeout:
                    raise CloudError("O QR expirou sem ser lido")
                continue
            if r.status_code == 200:
                break
            if time.time() - start > self._lp_timeout:
                raise CloudError(f"Login falhou (HTTP {r.status_code})")
        data = _json(r.text)
        self.user_id = data["userId"]
        self.ssecurity = data["ssecurity"]
        r = self.session.get(data["location"], headers={"content-type": "application/x-www-form-urlencoded"})
        if r.status_code != 200 or "serviceToken" not in r.cookies:
            raise CloudError("Não consegui obter o serviceToken")
        self.service_token = r.cookies["serviceToken"]

    # --- pedidos encriptados ---

    def _signed_nonce(self, nonce: str) -> str:
        h = hashlib.sha256(base64.b64decode(self.ssecurity) + base64.b64decode(nonce))
        return base64.b64encode(h.digest()).decode()

    @staticmethod
    def _enc_signature(path: str, signed_nonce: str, params: dict) -> str:
        parts = ["POST", path] + [f"{k}={v}" for k, v in params.items()] + [signed_nonce]
        return base64.b64encode(hashlib.sha1("&".join(parts).encode()).digest()).decode()

    def call(self, server: str, path: str, data: dict) -> dict | None:
        url = f"https://{'' if server == 'cn' else server + '.'}api.io.mi.com/app{path}"
        millis = int(time.time() * 1000)
        nonce = base64.b64encode(os.urandom(8) + (millis // 60000).to_bytes(4, "big")).decode()
        signed = self._signed_nonce(nonce)

        params = {"data": json.dumps(data)}
        params["rc4_hash__"] = self._enc_signature(path, signed, params)
        params = {k: base64.b64encode(_rc4(signed, v.encode())).decode() for k, v in params.items()}
        params["signature"] = self._enc_signature(path, signed, params)
        params["ssecurity"] = self.ssecurity
        params["_nonce"] = nonce

        r = self.session.post(url, params=params, headers={
            "Accept-Encoding": "identity",
            "User-Agent": self.agent,
            "Content-Type": "application/x-www-form-urlencoded",
            "x-xiaomi-protocal-flag-cli": "PROTOCAL-HTTP2",
            "MIOT-ENCRYPT-ALGORITHM": "ENCRYPT-RC4",
        }, cookies={
            "userId": str(self.user_id),
            "yetAnotherServiceToken": str(self.service_token),
            "serviceToken": str(self.service_token),
            "locale": "en_GB",
            "channel": "MI_APP_STORE",
        })
        if r.status_code != 200:
            return None
        return json.loads(_rc4(signed, base64.b64decode(r.text)))

    # --- dispositivos e chave ---

    def find_scooters(self) -> list[dict]:
        """Procura trotinetes em todos os servidores; para no primeiro que tiver alguma."""
        for server in SERVERS:
            homes = self.call(server, "/v2/homeroom/gethome", {
                "fg": True, "fetch_share": True, "fetch_share_dev": True, "limit": 300, "app_ver": 7})
            if not homes or homes.get("code") != 0:
                continue
            found = []
            for home in (homes.get("result") or {}).get("homelist") or []:
                devs = self.call(server, "/v2/home/home_device_list", {
                    "home_owner": home.get("uid", self.user_id), "home_id": home["id"],
                    "limit": 200, "get_split_device": True, "support_smart_home": True})
                for d in ((devs or {}).get("result") or {}).get("device_info") or []:
                    if "scooter" in (d.get("model") or ""):
                        found.append({"server": server, "did": d["did"], "model": d.get("model"),
                                      "name": d.get("name"), "mac": (d.get("mac") or "").upper()})
            if found:
                return found
        return []

    def bluetooth_key(self, server: str, did: str, pin: str | None = None) -> bytes:
        """Devolve a ltmk (32 bytes). Lança CloudError('PIN') se a chave estiver protegida por PIN."""
        resp = self.call(server, "/share/askbluetoothkey", {"type": "own", "did": str(did), "keyid": 0})
        if not resp or resp.get("code") != 0:
            raise CloudError(f"A Xiaomi recusou o pedido da chave: {resp}")
        result = resp.get("result") or {}
        key_hex, kind = result.get("key"), result.get("encrypt_type", 0)
        if not key_hex:
            raise CloudError("A resposta não traz a chave")
        if kind == 0:
            ltmk = bytes.fromhex(key_hex)
        elif kind == 1:
            if not pin:
                raise CloudError("PIN")
            dec = Cipher(algorithms.AES(hashlib.md5(pin.encode()).digest()), modes.CBC(LTMK_PIN_IV)).decryptor()
            ltmk = dec.update(bytes.fromhex(key_hex)) + dec.finalize()
        else:
            raise CloudError(f"Tipo de chave desconhecido: encrypt_type={kind}")
        if len(ltmk) != 32:
            raise CloudError(f"A chave tem {len(ltmk)} bytes; esperava 32")
        return ltmk


def _json(text: str) -> dict:
    return json.loads(text.replace("&&&START&&&", ""))

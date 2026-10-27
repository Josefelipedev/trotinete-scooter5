"""Passo 2: ir buscar a chave Bluetooth da trotinete à conta Xiaomi (só uma vez).

Mostra um QR code; lê-o com a app Mi Home (Perfil > ícone de leitura) na conta
onde a trotinete está associada. A chave fica em secrets/ltmk_<MAC>.hex e nunca
sai deste PC. A sessão da Xiaomi fica em secrets/sessao.json para não repetir o QR.

Corre-o num terminal normal (não em segundo plano): se a chave tiver PIN, o PIN
é pedido sem aparecer no ecrã.

    uv run chave.py [MAC]
"""

import os
import subprocess
import sys
from getpass import getpass

from trotinete.cloud import CloudError, XiaomiCloud

SECRETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "secrets")
SESSION = os.path.join(SECRETS, "sessao.json")


def qr_login() -> XiaomiCloud:
    cloud = XiaomiCloud()
    qr_url, login_url = cloud.start_qr_login()
    qr_path = os.path.join(SECRETS, "qr.png")
    with open(qr_path, "wb") as f:
        f.write(cloud.session.get(qr_url).content)
    subprocess.Popen(["xdg-open", qr_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"Abri o QR code ({qr_path}).")
    print("Lê-o com a app Mi Home no telemóvel e confirma o login.")
    print(f"(Ou abre isto num browser com a conta Xiaomi já iniciada: {login_url})")
    print("\nÀ espera...")
    cloud.wait_qr_login()
    os.remove(qr_path)
    cloud.save(SESSION)
    print("Login feito.\n")
    return cloud


def main():
    os.makedirs(SECRETS, mode=0o700, exist_ok=True)

    cloud = XiaomiCloud.load(SESSION)
    try:
        scooters = cloud.find_scooters() if cloud else []
    except Exception:  # sessão expirada: a Xiaomi responde com algo que não se decifra
        scooters = []
    if scooters:
        print("Usei a sessão Xiaomi guardada.\n")
    else:
        cloud = qr_login()
        scooters = cloud.find_scooters()
    if not scooters:
        print("Não encontrei nenhuma trotinete nesta conta.")
        return
    for i, s in enumerate(scooters):
        print(f"  [{i}] {s['name']}  modelo={s['model']}  mac={s['mac'] or '?'}  servidor={s['server']}")
    s = scooters[0] if len(scooters) == 1 else scooters[int(input("Qual? "))]

    try:
        ltmk = cloud.bluetooth_key(s["server"], s["did"])
    except CloudError as e:
        if str(e) != "PIN":
            raise
        if not sys.stdin.isatty():
            print("\nA chave está protegida por PIN. Corre este script num terminal normal para o escreveres.")
            return
        ltmk = cloud.bluetooth_key(s["server"], s["did"], getpass("\nA chave tem PIN. PIN da trotinete (não aparece): "))

    mac = s["mac"] or (sys.argv[1] if len(sys.argv) > 1 else input("A Xiaomi não deu o MAC. MAC da trotinete (do procurar.py): "))
    mac = mac.strip().upper()
    path = os.path.join(SECRETS, f"ltmk_{mac.replace(':', '')}.hex")
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        f.write(ltmk.hex())
    print(f"\nChave guardada em {path} (modelo {s['model']}).")
    print(f"Próximo passo: uv run ler.py {mac}")


if __name__ == "__main__":
    main()

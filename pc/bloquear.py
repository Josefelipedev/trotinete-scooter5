"""Testar o bloqueio no PC antes de o usar no telemóvel.

    uv run bloquear.py AA:BB:CC:DD:EE:FF sim   # bloqueia
    uv run bloquear.py AA:BB:CC:DD:EE:FF nao   # desbloqueia

Só bloqueia com a trotinete parada (em andamento = 0).
"""

import asyncio
import os
import sys

from trotinete.protocolo import BOOL, Scooter, ScooterError

SECRETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "secrets")


async def main():
    if len(sys.argv) < 3 or sys.argv[2] not in ("sim", "nao"):
        print(__doc__)
        return
    mac, bloquear = sys.argv[1].upper(), sys.argv[2] == "sim"
    ltmk = bytes.fromhex(open(os.path.join(SECRETS, f"ltmk_{mac.replace(':', '')}.hex")).read().strip())
    scooter = Scooter(mac)
    try:
        await scooter.connect()
        await scooter.login(ltmk)
        print(f"Antes: bloqueada={await scooter.get(2, 2)}  em andamento={await scooter.get(2, 7)}")
        if bloquear and await scooter.get(2, 7) != 0:
            print("Não bloqueio: a trotinete diz que está em andamento.")
            return
        await scooter.set(2, 2, BOOL, bloquear)
        print(f"Depois: bloqueada={await scooter.get(2, 2)}")
    except ScooterError as e:
        print(f"[!] {e}")
    finally:
        await scooter.disconnect()


if __name__ == "__main__":
    asyncio.run(main())

"""Passo 3: fazer login na trotinete e ler os dados (só leitura).

    uv run ler.py AA:BB:CC:DD:EE:FF

Precisa da chave em secrets/ltmk_<MAC>.hex (cria-a com chave.py).
"""

import asyncio
import os
import sys

from trotinete.protocolo import Scooter, ScooterError

SECRETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "secrets")

# (siid, piid) -> nome e unidade. Tabela levantada na 5 Pro/5 Max pelos projetos de referência.
PROPRIEDADES = [
    ((1, 2), "Bateria", "%"),
    ((1, 4), "Voltagem", "V"),
    ((1, 7), "Autonomia", "km"),
    ((1, 9), "Viagem atual", "km"),
    ((2, 6), "Total percorrido", "km"),
    ((2, 1), "Velocidade média", "km/h"),
    ((2, 9), "Velocidade máxima", "km/h"),
    ((1, 1), "Modo", ""),
    ((2, 2), "Bloqueada", ""),
    ((2, 7), "Em andamento (0 = parada)", ""),
    ((2, 4), "Luz traseira", ""),
    ((2, 5), "Travagem regenerativa", ""),
    ((3, 2), "Temperatura da bateria", "°C"),
    ((3, 3), "Temperatura da trotinete", "°C"),
    ((3, 10), "A carregar", ""),
    ((3, 11), "Ciclos de carga", ""),
    ((3, 12), "Saúde da bateria", "%"),
    ((1, 8), "Código de erro", ""),
    ((4, 5), "Firmware", ""),
    ((4, 4), "N.º de série", ""),
]

MODOS = {11: "Walk", 2: "Drive", 3: "Sport"}

# A Scooter 5 manda estes em centésimas (47,67 V chega como 4767).
CENTESIMAS = {(1, 4), (1, 5), (1, 6), (1, 7), (1, 9), (2, 1), (2, 6), (2, 9)}


def show(value, unit, name):
    if isinstance(value, bool):
        return "sim" if value else "não"
    if name == "Modo":
        return MODOS.get(value, str(value))
    if isinstance(value, float):
        return f"{value:.1f} {unit}".strip()
    return f"{value} {unit}".strip()


async def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    mac = sys.argv[1].upper()
    key_path = os.path.join(SECRETS, f"ltmk_{mac.replace(':', '')}.hex")
    if not os.path.exists(key_path):
        print(f"Não encontrei a chave {key_path}. Corre primeiro: uv run chave.py")
        return
    ltmk = bytes.fromhex(open(key_path).read().strip())

    scooter = Scooter(mac)
    print(f"A ligar a {mac}...")
    try:
        await scooter.connect()
        print("A fazer login...")
        await scooter.login(ltmk)
        print("Login aceite!\n")
        for (siid, piid), name, unit in PROPRIEDADES:
            try:
                value = await scooter.get(siid, piid)
                if (siid, piid) in CENTESIMAS:
                    value = value / 100
                print(f"  {name:<26} {show(value, unit, name)}")
            except ScooterError as e:
                print(f"  {name:<26} [!] {e}")
    except ScooterError as e:
        print(f"[!] {e}")
    finally:
        await scooter.disconnect()


if __name__ == "__main__":
    asyncio.run(main())

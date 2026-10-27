"""Passo 1: encontrar a trotinete e ler o que ela mostra sem login.

Só lê, nunca escreve nada na trotinete. Serve para confirmar que a Scooter 5
tem a mesma organização Bluetooth que a 5 Pro/5 Max (serviço FE95).

    uv run procurar.py            # procura e liga-se à primeira que encontrar
    uv run procurar.py AA:BB:...  # liga-se a esta morada
"""

import asyncio
import sys

from bleak import BleakClient, BleakScanner

MI_SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb"


def short_id(uuid: str) -> int:
    return int(str(uuid).replace("-", "")[:8], 16) & 0xFFFF


async def scan(seconds: float = 8.0):
    print(f"A procurar durante {seconds:.0f}s (liga a trotinete e deixa-a perto do PC)...")
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    scooters = []
    for device, adv in found.values():
        name = adv.local_name or device.name or ""
        is_mi = MI_SERVICE in adv.service_data or MI_SERVICE in adv.service_uuids
        if is_mi or "scooter" in name.lower():
            scooters.append((device, adv, name))
    scooters.sort(key=lambda x: x[1].rssi, reverse=True)
    for device, adv, name in scooters:
        print(f"  {device.address}  sinal={adv.rssi} dBm  nome={name or '(sem nome)'}")
    return scooters


async def inspect(address: str):
    print(f"\nA ligar a {address}...")
    async with BleakClient(address, timeout=20.0) as client:
        mi = next((s for s in client.services if str(s.uuid).lower() == MI_SERVICE), None)
        if mi is None:
            print("  [!] Não tem o serviço FE95: não é o protocolo da série 5.")
            for s in client.services:
                print(f"      serviço {s.uuid}")
            return
        chars = {short_id(c.uuid): c for c in mi.characteristics}
        print("  Serviço FE95 encontrado. Características:")
        for sid, c in sorted(chars.items()):
            print(f"    0x{sid:04x}  {', '.join(c.properties)}")

        expected = {0x0004, 0x0010, 0x0016, 0x001A, 0x001B, 0x001C}
        missing = expected - chars.keys()
        if missing:
            print(f"  [!] Faltam {[f'0x{m:04x}' for m in sorted(missing)]} (a 5 Pro tem todas)")
        else:
            print("  Tem todas as características da 5 Pro.")

        if 0x0004 in chars:
            raw = await client.read_gatt_char(chars[0x0004])
            print(f"  Firmware Bluetooth: {raw.decode('ascii', 'replace').strip(chr(0) + ' ')}")

        # 0x001c responde a pedidos simples sem login: [opcode] -> [?][len][dados]
        if 0x001C in chars:
            replies: asyncio.Queue[bytes] = asyncio.Queue()
            await client.start_notify(chars[0x001C], lambda _, d: replies.put_nowait(bytes(d)))
            for opcode, label in ((1, "Firmware do controlador"), (3, "Hardware")):
                await client.write_gatt_char(chars[0x001C], bytes([opcode]), response=False)
                try:
                    r = await asyncio.wait_for(replies.get(), timeout=2.0)
                except asyncio.TimeoutError:
                    print(f"  {label}: sem resposta")
                    continue
                if r and r[0] != 0xFF and len(r) >= 2:
                    value = r[2:2 + r[1]]
                    text = value.decode("ascii", "replace") if all(32 <= b < 127 for b in value) else value.hex()
                    print(f"  {label}: {text}")
                else:
                    print(f"  {label}: erro ({r.hex()})")
            await client.stop_notify(chars[0x001C])


async def main():
    if len(sys.argv) > 1:
        await inspect(sys.argv[1])
        return
    scooters = await scan()
    if not scooters:
        print("Nenhuma trotinete encontrada. Está ligada? Fecha a Mi Home no telemóvel (só aceita uma ligação).")
        return
    await inspect(scooters[0][0].address)


if __name__ == "__main__":
    asyncio.run(main())

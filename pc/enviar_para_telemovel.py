"""Instala a app no telemóvel (por cabo USB) e passa-lhe o MAC e a chave da trotinete.

Precisa da depuração USB ligada no telemóvel (Opções de programador).

    uv run enviar_para_telemovel.py AA:BB:CC:DD:EE:FF
"""

import os
import subprocess
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
APK = os.path.join(AQUI, "..", "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
ATIVIDADE = "dev.josefelipe.trotinete/.ui.MainActivity"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    mac = sys.argv[1].upper()
    chave = open(os.path.join(AQUI, "secrets", f"ltmk_{mac.replace(':', '')}.hex")).read().strip()

    if not os.path.exists(APK):
        print("Falta compilar a app: cd android && ./gradlew :app:assembleDebug")
        return
    print("A instalar a app...")
    subprocess.run(["adb", "install", "-r", APK], check=True)
    print("A enviar o MAC e a chave...")
    subprocess.run(["adb", "shell", "am", "start", "-n", ATIVIDADE, "--es", "mac", mac, "--es", "chave", chave],
                   check=True, stdout=subprocess.DEVNULL)
    print("Feito. Abre a app no telemóvel e aceita as permissões de Bluetooth e notificações.")


if __name__ == "__main__":
    main()

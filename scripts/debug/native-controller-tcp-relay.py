#!/usr/bin/env python3
"""Transparent stdio/TCP leg of the isolated Native SSH acceptance tunnel."""
import os
import socket
import sys
import threading

port = int(sys.argv[1])
if not 9800 <= port <= 9899:
    raise SystemExit('Use a registered port from the development reserved range')
with socket.create_connection(('127.0.0.1', port), timeout=5) as connection:
    connection.settimeout(None)

    def send():
        try:
            while data := os.read(0, 65536):
                connection.sendall(data)
            connection.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    threading.Thread(target=send, daemon=True).start()
    while data := connection.recv(65536):
        while data:
            count = os.write(1, data)
            data = data[count:]

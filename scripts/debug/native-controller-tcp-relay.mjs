#!/usr/bin/env node
// Run with Windows Node. Reserve both ports first; bind only host loopback.
// Arguments: Linux Python relay path, target SSH port, listen port, WSL distro.
import net from 'node:net';
import { spawn } from 'node:child_process';
const [relayPath, target, listen, distro] = process.argv.slice(2);
if (!relayPath?.startsWith('/') || !distro || ![target, listen].every((value) => /^98\d{2}$/.test(value ?? ''))) {
  throw new Error('Expected Linux relay path, registered target/listen ports and WSL distro');
}
const clients = new Set();
const server = net.createServer((socket) => {
  if (clients.size >= 8) { socket.destroy(); return; }
  const child = spawn('wsl.exe', ['-d', distro, '--exec', '/usr/bin/python3', relayPath, target], {
    stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true
  });
  clients.add(socket);
  socket.pipe(child.stdin);
  child.stdout.pipe(socket);
  socket.on('error', () => {});
  child.stdin.on('error', () => socket.destroy());
  child.on('error', () => socket.destroy());
  child.on('exit', () => socket.destroy());
  socket.on('close', () => { clients.delete(socket); child.kill(); });
});
server.listen(Number(listen), '127.0.0.1', () => console.log(JSON.stringify({ windowsPid: process.pid, port: Number(listen) })));
process.on('SIGINT', () => { for (const socket of clients) socket.destroy(); server.close(); });

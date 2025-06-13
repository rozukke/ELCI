package net.rozukke.elci;

import java.io.*;
import java.net.*;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public class ServerListenerThread implements Runnable {

	public ServerSocketChannel serverSocket;

	public UnixDomainSocketAddress bindAddress;

	public boolean running = true;

	private final ELCIPlugin plugin;

	// TODO(feat): Replace `SocketAddress` argument with `Path path`
	public ServerListenerThread(ELCIPlugin plugin, SocketAddress _bindAddress) throws IOException {
		this.plugin = plugin;

		Path path = Path.of("/tmp/elci-proxy");
		Files.deleteIfExists(path);

		this.bindAddress = UnixDomainSocketAddress.of(path);
		this.serverSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		this.serverSocket.bind(this.bindAddress);
	}

	public void run() {
		while (running) {
			try {
				SocketChannel newConnection = serverSocket.accept();
				if (!running) return;
				plugin.handleConnection(new RemoteSession(plugin, newConnection));
			} catch (Exception e) {
				// if the server thread is still running raise an error
				if (running) {
					plugin.getLogger().warning("Error creating new connection");
					e.printStackTrace();
				}
			}
		}
		try {
			serverSocket.close();
		} catch (Exception e) {
			plugin.getLogger().warning("Error closing server socket");
			e.printStackTrace();
		}
	}
}

package net.pierreroudier.pacnas;

import io.vertx.core.datagram.DatagramSocket;

import java.util.List;
import java.util.Vector;

import org.xbill.DNS.Message;

public class RecursionContext {
  List<NameserverCoordinate> nameserversToUse = new Vector<NameserverCoordinate>();
  int infiniteLoopProtection;
  Message remoteServerQueryMessage;
  DatagramSocket socket;
  org.xbill.DNS.Message remoteServerResponse;

  static public class NameserverCoordinate {
    private String name;
    private String ip;

    public NameserverCoordinate(String name) {
      this.name = name;
    }

    public NameserverCoordinate(String name, String ip) {
      this(name);
      this.ip = ip;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getIp() {
      return ip;
    }

    public void setIp(String ip) {
      this.ip = ip;
    }

    public String toString() {
      return name + " (" + ip + ")";
    }
  }
}

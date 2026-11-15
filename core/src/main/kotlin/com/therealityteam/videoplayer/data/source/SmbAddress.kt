package com.therealityteam.videoplayer.data.source

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection

/**
 * Connect to an SMB server named as `nas` or `nas:4445` — a port other than 445, which a NAS
 * behind a tunnel or a Samba in a container may listen on. An address with more than one colon
 * (IPv6) is taken whole, on the standard port.
 */
fun SMBClient.connectTo(address: String): Connection {
    val port = address.substringAfterLast(':', "").toIntOrNull()
        ?.takeIf { address.count { it == ':' } == 1 && it in 1..65535 }
    return if (port != null) connect(address.substringBeforeLast(':'), port) else connect(address)
}

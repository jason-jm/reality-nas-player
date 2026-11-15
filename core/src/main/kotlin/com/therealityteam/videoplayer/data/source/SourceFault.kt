package com.therealityteam.videoplayer.data.source

import java.io.IOException

/**
 * Why a source could not be reached, as a CAUSE rather than a sentence.
 *
 * The UI writes the sentence, in the reader's language. Two things went wrong while it was a
 * sentence: the same four `contains("timed out")` ladders were written out twice and drifted, and
 * one of them recognised an error our own code had raised by looking for a Chinese phrase in it
 * (`m.contains("共享名")`) — which stops being true the moment the app speaks anything else.
 */
enum class SourceFault {
    /** Wrong username or password, or no permission on that share. */
    AUTH,

    /** The host answered, but has no share by that name. */
    NO_SHARE,

    /** The share is there, the folder inside it is not — a path gone stale by a rename or a move. */
    NO_FOLDER,

    /** The address names a host but no share — smb://192.168.1.100 with nothing after it. */
    NO_ADDRESS,

    /** Nothing answered: wrong address, host asleep, or a network that does not route there. */
    UNREACHABLE,

    /** The server answered with an error of its own; [SourceException.message] carries the detail. */
    SERVER,

    /**
     * An SFTP server's identity is not the one it showed the first time: its system was reinstalled —
     * or something between is pretending to be it. Nothing is sent to it, the password least of all.
     */
    HOST_CHANGED,

    /**
     * An NFS server would not take this device: its address is not in the export's list, or the
     * export only answers privileged ports (below 1024), which an app may not open.
     */
    NFS_REFUSED,

    /** Anything else — the raw message is the best that can be said. */
    UNKNOWN,
}

/** An error whose cause we already know, so nothing has to be recovered from its wording. */
class SourceException(val fault: SourceFault, message: String? = null, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * The cause behind a throwable, whether it says so itself or has to be read out of an SMB/HTTP
 * library's own message. Kept in one place so every screen classifies alike.
 */
fun faultOf(e: Throwable): SourceFault {
    (e as? SourceException)?.let { return it.fault }
    val m = listOfNotNull(e.message, e.cause?.message, e.javaClass.simpleName).joinToString(" ")
    return when {
        m.contains("LOGON_FAILURE", true) || m.contains("ACCESS_DENIED", true) || m.startsWith("530") ||
            m.contains("Auth fail", true) ||
            m.contains("authenticate", true) || m.contains("STATUS_NO_SUCH_USER", true) ||
            m.contains("AuthException", false) -> SourceFault.AUTH
        m.contains("BAD_NETWORK_NAME", true) || m.contains("OBJECT_NAME_NOT_FOUND", true) -> SourceFault.NO_SHARE
        m.contains("ConnectException", true) || m.contains("unreachable", true) ||
            m.contains("timed out", true) || m.contains("timeout", true) -> SourceFault.UNREACHABLE
        else -> SourceFault.UNKNOWN
    }
}

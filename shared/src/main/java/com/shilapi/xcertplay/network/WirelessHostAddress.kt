package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Prefer a usable IPv4 address over a scoped IPv6 link-local address.
 *
 * The upstream implementation prefers the IPv6 link-local path whenever one exists. That
 * works on phone hotspots, but head-unit AP interfaces on many vendor ROMs (confirmed on
 * the Haval g6sa / Android 8.1 head unit) do not forward IPv6 multicast and never hand a
 * prefix to Wi-Fi clients. Once the address family is fixed to IPv6, DiPlay advertises
 * `_airplay._tcp` only over IPv6 (JmDNS bound to the IPv6 address), listens only on IPv6,
 * and sends the bare link-local IPv6 address to the iPhone over iAP2 — so the iPhone never
 * sees the announcement and never shows the CarPlay prompt.
 *
 * AP interfaces always serve DHCPv4, so a private IPv4 on the AP interface is the most
 * reliable announcement and listener address. IPv6 stays as the fallback for when no IPv4
 * exists.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    usableIpv4(addresses)?.let { return it }
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return null
}

private fun usableIpv4(addresses: List<InetAddress>): InetAddress? =
    addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }

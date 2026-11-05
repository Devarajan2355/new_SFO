package com.sanya.assistant

/**
 * GuardDns: the pure part of the Private DNS watch. No Android calls, so it is unit-tested.
 *
 * A DNS filter such as family-filter-dns.cleanbrowsing.org or adult-filter-dns.cleanbrowsing.org, set under
 * Settings > Private DNS, blocks adult sites in EVERY browser and app on the phone, whether or not Sanya is
 * running. Sanya cannot set it for you (Android reserves that), but she can notice if it is changed or switched
 * off, and she closes the settings page that changes it (see GuardLock).
 *
 * Android keeps it in two global settings:
 *   private_dns_mode       "hostname" (a provider you typed), "opportunistic" (Automatic) or "off"
 *   private_dns_specifier  the hostname
 */
object GuardDns {

    /** True once you have typed a provider hostname, so there is something to watch. */
    fun canWatch(mode: String?, host: String?): Boolean = mode == "hostname" && !host.isNullOrBlank()

    /** [watched] is the hostname she was told to watch ("" means not watching). */
    fun changed(mode: String?, host: String?, watched: String): Boolean {

        if (watched.isBlank()) return false

        return mode != "hostname" || (host ?: "").trim().lowercase() != watched.trim().lowercase()
    }
}

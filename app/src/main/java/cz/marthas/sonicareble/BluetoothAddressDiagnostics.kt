package cz.marthas.sonicareble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Diagnostic-only address tracking for the Sonicare BLE identity investigation.
 *
 * Android's public Bluetooth APIs expose the address used by BluetoothDevice
 * and its address type, but the actual LE identity-address accessor is
 * privileged. We therefore compare scan results with Android's bonded-device
 * list and persist observations across app launches instead of guessing.
 */
class BluetoothAddressDiagnostics(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ble_address_diagnostics",
        Context.MODE_PRIVATE
    )

    // Prevent repeated advertisements for the same address from filling the log.
    private val loggedThisRun = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    fun logBondedSonicareDevices(source: String = "BONDED_LIST") {
        val devices = try {
            adapter.bondedDevices.filter { isSonicare(it) }
        } catch (error: SecurityException) {
            logOnce(
                "permission-$source",
                "BLE ADDRESS [$source]: cannot inspect bonded devices; BLUETOOTH_CONNECT permission unavailable"
            )
            return
        } catch (error: Exception) {
            logOnce(
                "error-$source",
                "BLE ADDRESS [$source]: failed to inspect bonded devices: ${error.message}"
            )
            return
        }

        if (devices.isEmpty()) {
            logOnce(source + "|none", "BLE ADDRESS [$source]: no bonded Philips/Sonicare device found")
            return
        }

        devices.forEach { device ->
            record(device, source)
        }
    }

    @SuppressLint("MissingPermission")
    fun record(
        device: BluetoothDevice,
        source: String,
        rssi: Int? = null
    ) {
        try {
            val address = device.address.uppercase(Locale.US)
            val name = safeName(device)
            val addressType = addressTypeName(device)
            val addressPattern = addressPattern(address, addressType)
            val bondState = bondStateName(device)

            val bondedSonicare = try {
                adapter.bondedDevices.filter { isSonicare(it) }
            } catch (_: Exception) {
                emptyList()
            }

            val bondedAddresses = bondedSonicare.mapNotNull { safeAddress(it) }
            val matchesBondedList = bondedAddresses.any {
                it.equals(address, ignoreCase = true)
            }

            val bondedSummary = if (bondedSonicare.isEmpty()) {
                "none"
            } else {
                bondedSonicare.joinToString("; ") { bonded ->
                    "${safeName(bonded)}@${safeAddress(bonded) ?: "unknown"}" +
                        "(${bondStateName(bonded)},${addressTypeName(bonded)})"
                }
            }

            val rssiText = rssi?.let { "; rssi=$it dBm" } ?: ""
            val observationKey =
                "$source|$address|$addressType|$addressPattern|$bondState|$matchesBondedList|$bondedSummary"

            val previousAddress = preferences.getString(
                "last_address_${source.lowercase(Locale.US)}",
                null
            )

            if (loggedThisRun.add(observationKey)) {
                logger(
                    "BLE ADDRESS [$source]: address=$address; name=$name; " +
                        "type=$addressType; pattern=$addressPattern; bond=$bondState; " +
                        "matchesBondedList=$matchesBondedList; bondedSonicare=[$bondedSummary]$rssiText"
                )
            }

            if (previousAddress != null &&
                !previousAddress.equals(address, ignoreCase = true)
            ) {
                logger(
                    "BLE ADDRESS CHANGED [$source]: previous=$previousAddress; current=$address"
                )
            }

            preferences.edit()
                .putString("last_address_${source.lowercase(Locale.US)}", address)
                .apply()

            if (Build.VERSION.SDK_INT >= 36) {
                logOnce(
                    "identity-api-note",
                    "BLE ADDRESS NOTE: getIdentityAddressWithType() requires BLUETOOTH_PRIVILEGED; " +
                        "a normal third-party app cannot call it."
                )
            }
        } catch (error: SecurityException) {
            logOnce(
                "permission-record-$source",
                "BLE ADDRESS [$source]: Bluetooth address diagnostics blocked by permission: ${error.message}"
            )
        } catch (error: Exception) {
            logOnce(
                "error-record-$source",
                "BLE ADDRESS [$source]: diagnostics failed: ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun isSonicare(device: BluetoothDevice): Boolean {
        val name = safeName(device)
        return name.contains("Sonicare", ignoreCase = true) ||
            name.contains("Philips", ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: "<unnamed>"
        } catch (_: Exception) {
            "<name-unavailable>"
        }

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice): String? =
        try {
            device.address.uppercase(Locale.US)
        } catch (_: Exception) {
            null
        }

    @SuppressLint("MissingPermission")
    private fun addressTypeName(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT < 35) {
            return "UNAVAILABLE_API_${Build.VERSION.SDK_INT}"
        }

        return try {
            when (device.addressType) {
                BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "PUBLIC"
                BluetoothDevice.ADDRESS_TYPE_RANDOM -> "RANDOM"
                BluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> "ANONYMOUS"
                BluetoothDevice.ADDRESS_TYPE_UNKNOWN -> "UNKNOWN"
                else -> "OTHER(${device.addressType})"
            }
        } catch (error: SecurityException) {
            "PERMISSION_DENIED"
        } catch (error: Exception) {
            "ERROR(${error.javaClass.simpleName})"
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondStateName(device: BluetoothDevice): String =
        try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "BONDED"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_NONE -> "NONE"
                else -> "UNKNOWN(${device.bondState})"
            }
        } catch (_: Exception) {
            "UNAVAILABLE"
        }

    private fun addressPattern(address: String, addressType: String): String {
        if (addressType != "RANDOM") {
            return "not-applicable"
        }

        val firstOctet = address.substringBefore(':').toIntOrNull(16)
            ?: return "unparsed"

        return when (firstOctet and 0xC0) {
            0x40 -> "RPA-format (top bits 01)"
            0xC0 -> "static-random-format (top bits 11)"
            0x00 -> "non-resolvable-random-format (top bits 00)"
            else -> "reserved-random-format (top bits 10)"
        }
    }

    private fun logOnce(key: String, message: String) {
        if (loggedThisRun.add(key)) {
            logger(message)
        }
    }
}

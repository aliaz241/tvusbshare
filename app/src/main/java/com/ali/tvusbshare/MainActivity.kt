package com.ali.tvusbshare

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var partitionInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        partitionInput = findViewById(R.id.partitionInput)

        findViewById<Button>(R.id.checkButton).setOnClickListener { runCheck() }
        findViewById<Button>(R.id.enableButton).setOnClickListener { runEnable() }
        findViewById<Button>(R.id.disableButton).setOnClickListener { runDisable() }
    }

    /** Runs a list of shell lines inside a single `su` session and returns combined stdout+stderr. */
    private fun runAsRoot(commands: List<String>): String {
        val output = StringBuilder()
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            for (cmd in commands) {
                os.writeBytes(cmd + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            val out = BufferedReader(InputStreamReader(process.inputStream))
            val err = BufferedReader(InputStreamReader(process.errorStream))
            var line: String?
            while (out.readLine().also { line = it } != null) output.append(line).append("\n")
            while (err.readLine().also { line = it } != null) output.append("[err] ").append(line).append("\n")
            process.waitFor()
        } catch (e: Exception) {
            output.append("خطا در اجرای su: ${e.message}\n")
            output.append("مطمئن شو گوشی روت شده و این اپ دسترسی روت گرفته (مثلاً از Magisk).\n")
        }
        return output.toString()
    }

    private fun runCheck() {
        statusText.text = "در حال بررسی...\n"
        val cmds = listOf(
            "echo '--- پشتیبانی کرنل ---'",
            "if [ -d /sys/kernel/config/usb_gadget ]; then echo 'configfs: موجود'; else echo 'configfs: ندارد'; fi",
            "if [ -f /sys/class/android_usb/android0/functions ]; then echo 'legacy android_usb: موجود'; " +
                "echo \"functions فعلی: \$(cat /sys/class/android_usb/android0/functions)\"; " +
                "else echo 'legacy android_usb: ندارد'; fi",
            "echo ''",
            "echo '--- پارتیشن‌ها (دنبال microSD خارجی بگرد) ---'",
            "cat /proc/partitions",
            "echo ''",
            "echo '--- نقاط mount فعلی SD ---'",
            "mount | grep -i -E 'sdcard|external' "
        )
        statusText.text = runAsRoot(cmds)
    }

    private fun runEnable() {
        val partition = partitionInput.text.toString().trim()
        if (partition.isEmpty()) {
            statusText.text = "اول مسیر پارتیشن رو وارد کن (با دکمه «بررسی» پیداش کن)، مثلا:\n/dev/block/mmcblk1p1"
            return
        }

        val cmds = listOf(
            // Unmount so Android releases exclusive access to the filesystem first
            "umount /storage/sdcard1 2>/dev/null",
            "umount \$(readlink -f /storage/*-* 2>/dev/null) 2>/dev/null",

            // Try configfs (modern) first, fall back to legacy android_usb
            "if [ -d /sys/kernel/config/usb_gadget ]; then " +
                "mkdir -p /sys/kernel/config/usb_gadget/g1 && cd /sys/kernel/config/usb_gadget/g1 && " +
                "echo 0x18d1 > idVendor; echo 0x4ee1 > idProduct; " +
                "mkdir -p functions/mass_storage.0 && " +
                "echo '$partition' > functions/mass_storage.0/lun.0/file && " +
                "mkdir -p configs/c.1 && " +
                "ln -s functions/mass_storage.0 configs/c.1/ 2>/dev/null; " +
                "UDC_NAME=\$(ls /sys/class/udc | head -n1) && echo \$UDC_NAME > UDC && " +
                "echo 'نتیجه: UMS با روش configfs فعال شد'; " +
            "elif [ -f /sys/class/android_usb/android0/functions ]; then " +
                "echo mass_storage > /sys/class/android_usb/android0/functions && " +
                "echo '$partition' > /sys/class/android_usb/android0/f_mass_storage/lun/file && " +
                "echo 1 > /sys/class/android_usb/android0/enable && " +
                "echo 'نتیجه: UMS با روش legacy فعال شد'; " +
            "else " +
                "echo 'نتیجه: این کرنل هیچ‌کدوم از دو روش رو ساپورت نمی‌کنه'; " +
            "fi"
        )
        statusText.text = "در حال فعال‌سازی...\n" + runAsRoot(cmds) +
            "\nحالا سیم USB رو به تلویزیون وصل کن."
    }

    private fun runDisable() {
        val cmds = listOf(
            "if [ -f /sys/class/android_usb/android0/enable ]; then echo 0 > /sys/class/android_usb/android0/enable; fi",
            "if [ -d /sys/kernel/config/usb_gadget/g1 ]; then " +
                "cd /sys/kernel/config/usb_gadget/g1 && " +
                "echo '' > UDC 2>/dev/null; " +
                "rm -f configs/c.1/mass_storage.0 2>/dev/null; " +
                "rmdir configs/c.1 2>/dev/null; " +
                "rmdir functions/mass_storage.0 2>/dev/null; " +
                "cd .. && rmdir g1 2>/dev/null; " +
            "fi",
            "echo 'UMS غیرفعال شد — گوشی به حالت عادی (MTP) برگشت'"
        )
        statusText.text = runAsRoot(cmds)
    }
}

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
                os.write(cmd.toByteArray(Charsets.UTF_8))
                os.writeBytes("\n")
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
        var partition = partitionInput.text.toString().trim()
        if (partition.isEmpty()) {
            statusText.text = "اول مسیر پارتیشن رو وارد کن (با دکمه «بررسی» پیداش کن)، مثلا:\n/dev/block/mmcblk0p1"
            return
        }
        if (!partition.startsWith("/")) partition = "/$partition"

        // Works on the ROM's existing gadget at /config/usb_gadget/g1 (config b.1):
        // unbind, swap the MTP/ADB function links for mass_storage (read-only LUN), rebind.
        // No unmount on purpose: unmounting the in-use card wedges apps on this ROM,
        // and the read-only LUN keeps the TV from writing anyway.
        val cmds = listOf(
            "G=/config/usb_gadget/g1; C=\$G/configs/b.1; F=\$G/functions/mass_storage.0; P='$partition'",
            "if [ ! -d \$G ]; then echo 'خطا: گجت USB پیدا نشد'; exit 1; fi",
            "if [ ! -b \$P ]; then echo 'خطا: این پارتیشن وجود نداره:' \$P; exit 1; fi",
            "echo '' > \$G/UDC",
            "for l in \$C/*; do case \$(readlink \$l) in *ffs.mtp*|*ffs.adb*) rm -f \$l;; esac; done",
            "mkdir -p \$F",
            "echo '' > \$F/lun.0/file 2>/dev/null",
            "echo 1 > \$F/lun.0/ro",
            "echo \$P > \$F/lun.0/file",
            "ln -s \$F \$C/mass_storage.0",
            "echo \$(ls /sys/class/udc | head -n 1) > \$G/UDC",
            "echo 'نتیجه: UMS فعال شد'"
        )
        statusText.text = "در حال فعال‌سازی...\n" + runAsRoot(cmds) +
            "\nحالا سیم USB رو به تلویزیون وصل کن."
    }

    private fun runDisable() {
        val cmds = listOf(
            "G=/config/usb_gadget/g1; C=\$G/configs/b.1; F=\$G/functions/mass_storage.0",
            "if [ -d \$G ]; then echo '' > \$G/UDC; fi",
            "rm -f \$C/mass_storage.0 2>/dev/null",
            "echo '' > \$F/lun.0/file 2>/dev/null",
            "ln -s \$G/functions/ffs.mtp \$C/ffs.mtp 2>/dev/null",
            "ln -s \$G/functions/ffs.adb \$C/ffs.adb 2>/dev/null",
            "echo \$(ls /sys/class/udc | head -n 1) > \$G/UDC",
            "echo 'UMS غیرفعال شد — گوشی به حالت عادی (MTP) برگشت'"
        )
        statusText.text = runAsRoot(cmds)
    }
}

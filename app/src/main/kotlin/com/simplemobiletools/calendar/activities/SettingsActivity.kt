package com.simplemobiletools.calendar.activities

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.support.v4.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.calendar.CalendarScopes
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.asynctasks.FetchGoogleEventsTask
import com.simplemobiletools.calendar.dialogs.CustomEventReminderDialog
import com.simplemobiletools.calendar.dialogs.SnoozePickerDialog
import com.simplemobiletools.calendar.extensions.*
import com.simplemobiletools.calendar.helpers.FONT_SIZE_LARGE
import com.simplemobiletools.calendar.helpers.FONT_SIZE_MEDIUM
import com.simplemobiletools.calendar.helpers.FONT_SIZE_SMALL
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.extensions.updateTextColors
import com.simplemobiletools.commons.models.RadioItem
import kotlinx.android.synthetic.main.activity_settings.*

class SettingsActivity : SimpleActivity() {
    private val GET_RINGTONE_URI = 1
    private val ACCOUNTS_PERMISSION = 2
    private val REQUEST_ACCOUNT_NAME = 3
    private val REQUEST_GOOGLE_PLAY_SERVICES = 4

    lateinit var res: Resources
    private var mStoredPrimaryColor = 0

    companion object {
        val REQUEST_AUTHORIZATION = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        res = resources
    }

    override fun onResume() {
        super.onResume()

        setupCustomizeColors()
        setupManageEventTypes()
        setupHourFormat()
        setupSundayFirst()
        setupWeekNumbers()
        setupGoogleSync()
        setupWeeklyStart()
        setupWeeklyEnd()
        setupVibrate()
        setupReminderSound()
        setupSnoozeDelay()
        setupEventReminder()
        setupDisplayPastEvents()
        setupFontSize()
        updateTextColors(settings_holder)
        checkPrimaryColor()
    }

    override fun onPause() {
        super.onPause()
        mStoredPrimaryColor = config.primaryColor
    }

    private fun checkPrimaryColor() {
        if (config.primaryColor != mStoredPrimaryColor) {
            dbHelper.getEventTypes {
                if (it.size == 1) {
                    val eventType = it[0]
                    eventType.color = config.primaryColor
                    dbHelper.updateEventType(eventType)
                }
            }
        }
    }

    private fun setupCustomizeColors() {
        settings_customize_colors_holder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupManageEventTypes() {
        settings_manage_event_types_holder.setOnClickListener {
            startActivity(Intent(this, ManageEventTypesActivity::class.java))
        }
    }

    private fun setupHourFormat() {
        settings_hour_format.isChecked = config.use24hourFormat
        settings_hour_format_holder.setOnClickListener {
            settings_hour_format.toggle()
            config.use24hourFormat = settings_hour_format.isChecked
        }
    }

    private fun setupGoogleSync() {
        settings_google_sync.isChecked = isGoogleSyncActive()
        settings_google_sync_holder.setOnClickListener {
            if (config.googleSync) {
                ConfirmationDialog(this, getString(R.string.google_sync_disabling), positive = R.string.ok, negative = R.string.cancel) {
                    dbHelper.deleteAllGoogleSyncEvents()
                    toggleGoogleSync()
                }
            } else {
                if (isOnline()) {
                    ConfirmationDialog(this, getString(R.string.google_sync_testing), positive = R.string.ok, negative = 0) {
                        toggleGoogleSync()
                    }
                } else {
                    toast(R.string.cannot_while_offline)
                }
            }
        }
    }

    private fun toggleGoogleSync() {
        settings_google_sync.toggle()
        config.googleSync = settings_google_sync.isChecked

        if (settings_google_sync.isChecked) {
            tryEnablingSync()
        } else {
            disableGoogleSync()
        }
    }

    private fun setupSundayFirst() {
        settings_sunday_first.isChecked = config.isSundayFirst
        settings_sunday_first_holder.setOnClickListener {
            settings_sunday_first.toggle()
            config.isSundayFirst = settings_sunday_first.isChecked
        }
    }

    private fun setupWeeklyStart() {
        settings_start_weekly_at.text = getHoursString(config.startWeeklyAt)
        settings_start_weekly_at_holder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            (0..24).mapTo(items) { RadioItem(it, getHoursString(it)) }

            RadioGroupDialog(this@SettingsActivity, items, config.startWeeklyAt) {
                if (it as Int >= config.endWeeklyAt) {
                    toast(R.string.day_end_before_start)
                } else {
                    config.startWeeklyAt = it
                    settings_start_weekly_at.text = getHoursString(it)
                }
            }
        }
    }

    private fun setupWeeklyEnd() {
        settings_end_weekly_at.text = getHoursString(config.endWeeklyAt)
        settings_end_weekly_at_holder.setOnClickListener {
            val items = ArrayList<RadioItem>()
            (0..24).mapTo(items) { RadioItem(it, getHoursString(it)) }

            RadioGroupDialog(this@SettingsActivity, items, config.endWeeklyAt) {
                if (it as Int <= config.startWeeklyAt) {
                    toast(R.string.day_end_before_start)
                } else {
                    config.endWeeklyAt = it
                    settings_end_weekly_at.text = getHoursString(it)
                }
            }
        }
    }

    private fun setupWeekNumbers() {
        settings_week_numbers.isChecked = config.displayWeekNumbers
        settings_week_numbers_holder.setOnClickListener {
            settings_week_numbers.toggle()
            config.displayWeekNumbers = settings_week_numbers.isChecked
        }
    }

    private fun setupReminderSound() {
        val noRingtone = res.getString(R.string.no_ringtone_selected)
        if (config.reminderSound.isEmpty()) {
            settings_reminder_sound.text = noRingtone
        } else {
            settings_reminder_sound.text = RingtoneManager.getRingtone(this, Uri.parse(config.reminderSound))?.getTitle(this) ?: noRingtone
        }
        settings_reminder_sound_holder.setOnClickListener {
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, res.getString(R.string.reminder_sound))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(config.reminderSound))

                if (resolveActivity(packageManager) != null)
                    startActivityForResult(this, GET_RINGTONE_URI)
                else {
                    toast(R.string.no_ringtone_picker)
                }
            }
        }
    }

    private fun setupVibrate() {
        settings_vibrate.isChecked = config.vibrateOnReminder
        settings_vibrate_holder.setOnClickListener {
            settings_vibrate.toggle()
            config.vibrateOnReminder = settings_vibrate.isChecked
        }
    }

    private fun setupSnoozeDelay() {
        updateSnoozeText()
        settings_snooze_delay_holder.setOnClickListener {
            SnoozePickerDialog(this, config.snoozeDelay) {
                config.snoozeDelay = it
                updateSnoozeText()
            }
        }
    }

    private fun updateSnoozeText() {
        settings_snooze_delay.text = res.getQuantityString(R.plurals.minutes, config.snoozeDelay, config.snoozeDelay)
    }

    private fun setupEventReminder() {
        var reminderMinutes = config.defaultReminderMinutes
        settings_default_reminder.text = getFormattedMinutes(reminderMinutes)
        settings_default_reminder_holder.setOnClickListener {
            showEventReminderDialog(reminderMinutes) {
                config.defaultReminderMinutes = it
                reminderMinutes = it
                settings_default_reminder.text = getFormattedMinutes(it)
            }
        }
    }

    private fun getHoursString(hours: Int): String {
        return if (hours < 10) {
            "0$hours:00"
        } else {
            "$hours:00"
        }
    }

    private fun setupDisplayPastEvents() {
        var displayPastEvents = config.displayPastEvents
        updatePastEventsText(displayPastEvents)
        settings_display_past_events_holder.setOnClickListener {
            CustomEventReminderDialog(this, displayPastEvents) {
                displayPastEvents = it
                config.displayPastEvents = it
                updatePastEventsText(it)
            }
        }
    }

    private fun updatePastEventsText(displayPastEvents: Int) {
        settings_display_past_events.text = getDisplayPastEventsText(displayPastEvents)
    }

    private fun getDisplayPastEventsText(displayPastEvents: Int): String {
        return if (displayPastEvents == 0)
            getString(R.string.never)
        else
            getFormattedMinutes(displayPastEvents, false)
    }

    private fun setupFontSize() {
        settings_font_size.text = getFontSizeText()
        settings_font_size_holder.setOnClickListener {
            val items = arrayListOf(
                    RadioItem(FONT_SIZE_SMALL, res.getString(R.string.small)),
                    RadioItem(FONT_SIZE_MEDIUM, res.getString(R.string.medium)),
                    RadioItem(FONT_SIZE_LARGE, res.getString(R.string.large)))

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                settings_font_size.text = getFontSizeText()
                updateWidgets()
                updateListWidget()
            }
        }
    }

    private fun getFontSizeText() = getString(when (config.fontSize) {
        FONT_SIZE_SMALL -> R.string.small
        FONT_SIZE_MEDIUM -> R.string.medium
        else -> R.string.large
    })

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == GET_RINGTONE_URI) {
                val uri = data?.getParcelableExtra<Parcelable>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri == null) {
                    config.reminderSound = ""
                } else {
                    settings_reminder_sound.text = RingtoneManager.getRingtone(this, uri as Uri)?.getTitle(this)
                    config.reminderSound = uri.toString()
                }
            } else if (requestCode == REQUEST_ACCOUNT_NAME && data?.extras != null) {
                val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                config.syncAccountName = accountName
                tryEnablingSync()
            } else if (requestCode == REQUEST_AUTHORIZATION) {
                tryEnablingSync()
            }
        } else if (resultCode == Activity.RESULT_CANCELED && requestCode == REQUEST_ACCOUNT_NAME) {
            disableGoogleSync()
        }
    }

    private fun tryEnablingSync() {
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices()
        } else if (!hasGetAccountsPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.GET_ACCOUNTS), ACCOUNTS_PERMISSION)
        } else if (config.syncAccountName.isEmpty()) {
            showAccountChooser()
        } else {
            FetchGoogleEventsTask(this).execute()
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        return connectionStatusCode == ConnectionResult.SUCCESS
    }

    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, connectionStatusCode, REQUEST_GOOGLE_PLAY_SERVICES).show()
        }
    }

    private fun disableGoogleSync() {
        settings_google_sync.isChecked = false
        config.googleSync = false
        config.syncAccountName = ""
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == ACCOUNTS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showAccountChooser()
            } else {
                disableGoogleSync()
            }
        }
    }

    private fun showAccountChooser() {
        if (config.syncAccountName.isEmpty()) {
            // more about oauth at https://developers.google.com/google-apps/calendar/auth
            val credential = GoogleAccountCredential.usingOAuth2(this, arrayListOf(CalendarScopes.CALENDAR)).setBackOff(ExponentialBackOff())
            startActivityForResult(credential.newChooseAccountIntent(), REQUEST_ACCOUNT_NAME)
        }
    }
}

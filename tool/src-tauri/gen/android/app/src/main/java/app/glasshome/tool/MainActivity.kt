package app.glasshome.tool

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  // Android drops multicast packets unless an app holds this lock; without it the network search
  // finds TVs only by scanning, not by their mDNS names.
  private var multicast: WifiManager.MulticastLock? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // A task on the TV can take minutes; a phone that turns its screen off may lose Wi-Fi or be
    // frozen in the background halfway. While Glass Home is open, the screen stays on.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun onResume() {
    super.onResume()
    if (multicast == null) {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicast = wifi.createMulticastLock("glasshome-search").apply {
        setReferenceCounted(false)
        acquire()
      }
    }
  }

  override fun onPause() {
    multicast?.release()
    multicast = null
    super.onPause()
  }
}

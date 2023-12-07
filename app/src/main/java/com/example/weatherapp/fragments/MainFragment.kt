package com.example.weatherapp.fragments

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.example.weatherapp.*
import com.example.weatherapp.adapters.ViewPagerAdapter
import com.example.weatherapp.adapters.WeatherModel
import com.example.weatherapp.databinding.FragmentMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.tabs.TabLayoutMediator
import com.squareup.picasso.Picasso
import org.json.JSONObject

class MainFragment : Fragment() {
    private lateinit var fLocationClient: FusedLocationProviderClient
    private var isWeatherLoad = false
    private val fragmentList = listOf(
        HoursFragment.newInstance(),
        DaysFragment.newInstance()
    )
    private val tabsList = listOf(
        "Hours",
        "Days"
    )
    private lateinit var pLauncher: ActivityResultLauncher<String>
    private lateinit var binding: FragmentMainBinding
    private val model: MainViewModel by activityViewModels()
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        clockAnimation()
        return binding.root
    }

    private fun clockAnimation() = with(binding) {
        val clockRotateAnimation =
            AnimationUtils.loadAnimation(context, R.anim.rotate_animation_infinite)
        clockRotateAnimation.duration = 2500
        ivClock.animation = clockRotateAnimation
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkPermission()
        init()
        updateCurrentCard()
    }

    private fun init() = with(binding) {
        fLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        val adapter = ViewPagerAdapter(activity as FragmentActivity, fragmentList)
        vpDates.adapter = adapter
        TabLayoutMediator(tlDates, vpDates) { tab, position ->
            tab.text = tabsList[position]
        }.attach()

        ibReload.setOnClickListener { view ->
            val rotateAnimation = AnimationUtils.loadAnimation(context, R.anim.rotate_animation_one)
            tlDates.selectTab(tlDates.getTabAt(0))
            checkLocation()
            view.animation = rotateAnimation
        }

        ibSearch.setOnClickListener { view ->
            DialogManager.searchByName(requireContext(), object : DialogManager.Listener {
                override fun onClick(name: String?) {
                    name?.let { requestWeatherData(it) }
                }
            })

            val valueAnimator = ValueAnimator.ofFloat(
                view.scaleX,
                view.scaleX - 0.2f,
                view.scaleX + 0.4f,
                view.scaleX
            )
            valueAnimator.addUpdateListener {
                val value = it.animatedValue as Float
                view.scaleY = value
                view.scaleX = value
            }
            valueAnimator.interpolator = LinearInterpolator()
            valueAnimator.duration = 300
            valueAnimator.start()
        }
    }

    private fun checkLocation() {
        if (isLocationEnabled()) {
            getLocation()
        } else {
            DialogManager.locationSettingsDialog(requireContext(), object : DialogManager.Listener {
                override fun onClick(name: String?) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            })
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager =
            activity?.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun getLocation() {
        if (!isLocationEnabled()) {
            Toast.makeText(requireContext(), "Location disabled!", Toast.LENGTH_SHORT).show()
            return
        }
        val ct = CancellationTokenSource()
        fLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, ct.token)
            .addOnCompleteListener {
                requestWeatherData("${it.result.latitude},${it.result.longitude}")
            }
    }

    private fun updateCurrentCard() = with(binding) {
        model.liveDataCurrent.observe(viewLifecycleOwner) {
            binding.tvDate.text
            tvDate.text = it.time
            tvCity.text = it.city
            tvCondition.text = it.condition
            tvCurrentTemp.text = it.currentTemp.ifEmpty {
                "${it.maxTemp.toFloat().toInt()}°C / " +
                        "${it.minTemp.toFloat().toInt()}°C"
            }
            tvMaxMinTemp.text =
                if (it.currentTemp.isEmpty()) "" else "${it.maxTemp}°C / ${it.minTemp}°C"
            Picasso.get().load("https:" + it.imageUrl).into(imWeather)
            showWeather()
        }
    }

    private fun showWeather() = with(binding) {
        ivClock.animation = null
        ivClock.visibility = View.GONE
        clMain.visibility = View.VISIBLE
    }

    private fun permissionListener() {
        pLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            Toast.makeText(activity, "Permission is $it", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermission() {
        if (!isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionListener()
            pLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestWeatherData(location: String) {
        val url = API_URL +
                API_KEY +
                API_START +
                location +
                API_DAYS +
                API_DAYS_COUNT +
                API_END
        val queue = Volley.newRequestQueue(context)
        val stringRequest = StringRequest(
            Request.Method.GET,
            url,
            { response ->
                parseWeatherData(response)
            },
            { error ->
                Log.d("MyLog", "Volley error: $error")
            }
        )
        queue.add(stringRequest)
    }

    private fun parseWeatherData(response: String) {
        val mainObject = JSONObject(response)
        val list = parseDays(mainObject)
        parseCurrentData(mainObject, list[0])
    }

    private fun parseDays(mainObject: JSONObject): List<WeatherModel> {
        val list = ArrayList<WeatherModel>()
        val daysArray = mainObject.getJSONObject("forecast").getJSONArray("forecastday")
        val name = mainObject.getJSONObject("location").getString("name")
        for (i in 0 until daysArray.length()) {
            val day = daysArray[i] as JSONObject
            val item = WeatherModel(
                name,
                day.getString("date"),
                day.getJSONObject("day")
                    .getJSONObject("condition").getString("text"),
                "",
                day.getJSONObject("day").getString("maxtemp_c"),
                day.getJSONObject("day").getString("mintemp_c"),
                day.getJSONObject("day")
                    .getJSONObject("condition").getString("icon"),
                day.getJSONArray("hour").toString()
            )
            list.add(item)
        }
        model.liveDataList.value = list
        return list
    }

    private fun parseCurrentData(mainObject: JSONObject, currentDate: WeatherModel) {
        val item = WeatherModel(
            mainObject.getJSONObject("location").getString("name"),
            mainObject.getJSONObject("current").getString("last_updated"),
            mainObject.getJSONObject("current")
                .getJSONObject("condition").getString("text"),
            mainObject.getJSONObject("current").getString("temp_c"),
            currentDate.maxTemp,
            currentDate.minTemp,
            mainObject.getJSONObject("current")
                .getJSONObject("condition").getString("icon"),
            currentDate.hours
        )
        model.liveDataCurrent.value = item
    }

    override fun onResume() {
        super.onResume()
        checkLocation()
    }

    companion object {
        @JvmStatic
        fun newInstance() = MainFragment()
    }
}
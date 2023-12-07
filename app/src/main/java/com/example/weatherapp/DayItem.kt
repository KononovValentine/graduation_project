package com.example.weatherapp

data class DayItem(
    val city: String,
    val date: String,
    val condition: String,
    val imageURrl: String,
    val currentTemp: String,
    val maxTemp: String,
    val minTemp: String,
    val hours: String
)

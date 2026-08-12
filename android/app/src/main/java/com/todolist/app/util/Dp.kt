package com.todolist.app.util

import android.content.Context

fun Float.dp(context: Context): Float = this * context.resources.displayMetrics.density
fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

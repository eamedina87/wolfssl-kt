package tech.medina.wolfssl_kt.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import tech.medina.wolfssl.kt.WolfSslKt

class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is home Fragment"
    }
    val text: LiveData<String> = _text

    init {
        viewModelScope.launch {
            WolfSslKt.state.collect {
                _text.value = it.toString()
            }
        }
    }
    fun prepareConnection() {
        WolfSslKt.prepareConnection()
    }
}

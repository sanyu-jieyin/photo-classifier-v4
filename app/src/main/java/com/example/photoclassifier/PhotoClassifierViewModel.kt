package com.example.photoclassifier

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoItem(val uri: Uri, val name: String, val mimeType: String)
data class FolderItem(val uri: Uri, val name: String)
data class FolderSlot(val index: Int, val folderItem: FolderItem? = null)

class PhotoClassifierViewModel(application: Application) : AndroidViewModel(application) {
    private val fileHelper = FileHelper(application)
    private val context get() = getApplication<Application>()

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _slots = MutableStateFlow(List(6) { FolderSlot(it) })
    val slots: StateFlow<List<FolderSlot>> = _slots.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    fun loadSourceFolder(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadProgress.value = 0
            _sourceName.value = DocumentFile.fromTreeUri(context, uri)?.name

            val list = withContext(Dispatchers.IO) {
                fileHelper.getPhotosFromFolder(uri)
            }

            _photos.value = list
            _currentIndex.value = 0
            _loadProgress.value = list.size
            _isLoading.value = false

            if (list.size >= 2000) {
                _toastMessage.value = "已加载前 2000 张，建议分批整理"
            }
        }
    }

    fun setSlotFolder(slotIndex: Int, folder: FolderItem) {
        _slots.value = _slots.value.map {
            if (it.index == slotIndex) it.copy(folderItem = folder) else it
        }
    }

    fun clearSlot(slotIndex: Int) {
        _slots.value = _slots.value.map {
            if (it.index == slotIndex) it.copy(folderItem = null) else it
        }
    }

    fun moveCurrentPhotoToSlot(slotIndex: Int) {
        val currentIdx = _currentIndex.value
        val photo = _photos.value.getOrNull(currentIdx) ?: return
        val slot = _slots.value.getOrNull(slotIndex) ?: return
        val targetUri = slot.folderItem?.uri ?: return

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                fileHelper.movePhoto(photo.uri, targetUri, photo.name)
            }
            if (success) {
                val mutable = _photos.value.toMutableList()
                mutable.removeAt(currentIdx)
                _photos.value = mutable
                if (_currentIndex.value >= _photos.value.size) {
                    _currentIndex.value = maxOf(0, _photos.value.size - 1)
                }
                _toastMessage.value = "已移动到「${slot.folderItem.name}」"
            } else {
                _toastMessage.value = "移动失败"
            }
        }
    }

    fun nextPhoto() {
        if (_currentIndex.value < _photos.value.size - 1) {
            _currentIndex.value++
        }
    }

    fun prevPhoto() {
        if (_currentIndex.value > 0) {
            _currentIndex.value--
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}

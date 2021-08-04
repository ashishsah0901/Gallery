package com.example.gallery.ui

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.gallery.adapter.InternalStorageAdapter
import com.example.gallery.adapter.SharedStorageAdapter
import com.example.gallery.data.InternalStoragePhoto
import com.example.gallery.data.SharedStoragePhoto
import com.example.gallery.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var internalStorageAdapter: InternalStorageAdapter
    private lateinit var externalStorageAdapter: SharedStorageAdapter

    private var readPermission = false
    private var writePermission = false

    private lateinit var permissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var contentObserver: ContentObserver

    private var deletedPhotoUri : Uri ?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        internalStorageAdapter = InternalStorageAdapter {
            lifecycleScope.launch {
                if (deletePhotoFromInternalStorage(it.name)) {
                    loadPhotosFromInternalStorageInRecyclerView()
                    Toast.makeText(this@MainActivity, "Photo deleted successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to delete photo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        externalStorageAdapter = SharedStorageAdapter {
            lifecycleScope.launch {
                deletePhotoFromExternalStorage(it.contentUri)
                deletedPhotoUri = it.contentUri
            }
        }

        setUpExternalStorageRecyclerView()
        initContentObserver()

        permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            readPermission = it[Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermission
            writePermission = it[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermission
            if(readPermission) {
                loadPhotosFromExternalStorageInRecyclerView()
            } else {
                Toast.makeText(this,"Can't read files without permission", Toast.LENGTH_LONG).show()
            }
        }
        updateOrRequestPermission()

        intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){
            if(it.resultCode == RESULT_OK) {
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    lifecycleScope.launch {
                        deletePhotoFromExternalStorage(deletedPhotoUri ?: return@launch)
                    }
                }
                Toast.makeText(this@MainActivity, "Photo deleted successfully", Toast.LENGTH_SHORT).show()
            }else {
                Toast.makeText(this@MainActivity, "Failed to delete photo", Toast.LENGTH_SHORT).show()
            }
        }

        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
            lifecycleScope.launch {
                val isPrivate = binding.switchPrivate.isChecked
                val isSuccessfullySaved = when {
                    isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it)
                    writePermission -> savePhotoToExternalStorage(UUID.randomUUID().toString(), it)
                    else -> false
                }
                if(isPrivate) {
                    loadPhotosFromInternalStorageInRecyclerView()
                }
                if(isSuccessfullySaved) {
                    Toast.makeText(this@MainActivity, "Photo saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch()
        }

        setUpInternalStorageRecyclerView()
        loadPhotosFromInternalStorageInRecyclerView()
        loadPhotosFromExternalStorageInRecyclerView()
    }

    private fun updateOrRequestPermission() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val hasWritePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val minSdkVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        readPermission = hasReadPermission
        writePermission = hasWritePermission || minSdkVersion
        val permissionsToRequest = mutableListOf<String>()
        if(!readPermission){
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if(!writePermission){
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if(permissionsToRequest.isNotEmpty()){
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setUpInternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = internalStorageAdapter
        layoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
    }

    private fun setUpExternalStorageRecyclerView() = binding.rvPublicPhotos.apply {
        adapter = externalStorageAdapter
        layoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL)
    }

    private fun initContentObserver() {
        contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                if(readPermission) {
                    loadPhotosFromExternalStorageInRecyclerView()
                }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }

    private fun loadPhotosFromInternalStorageInRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotosFromInternalStorage()
            internalStorageAdapter.submitList(photos)
        }
    }

    private fun loadPhotosFromExternalStorageInRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotosFromExternalStorage()
            externalStorageAdapter.submitList(photos)
        }
    }

    private suspend fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            val imageCollection = sdk29OrAbove {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpeg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, bmp.width)
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
            }
            try {
                contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                    contentResolver.openOutputStream(uri).use { stream ->
                        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                            throw IOException("Failed yo save bitmap")
                        }
                    }
                } ?: throw IOException("Couldn't create media store entry")
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun savePhotoToInternalStorage(fileName: String, bmp: Bitmap) : Boolean {
        return withContext(Dispatchers.IO) {
            try {
                openFileOutput("$fileName.jpg", MODE_PRIVATE).use {
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                        throw IOException("Couldn't Save File")
                    }
                }
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun loadPhotosFromExternalStorage() : List<SharedStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val collection = sdk29OrAbove {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            val photos = mutableListOf<SharedStoragePhoto>()
            contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DISPLAY_NAME} ASC") ?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val displayName = it.getString(displayNameColumn)
                    val width = it.getInt(widthColumn)
                    val height = it.getInt(heightColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photos.add(SharedStoragePhoto(id,displayName,width,height,contentUri))
                }
                photos.toList()
            } ?: listOf()
        }
    }

    private suspend fun loadPhotosFromInternalStorage() : List<InternalStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val files = filesDir.listFiles()
            files?.filter { it.canRead() && it.isFile && it.name.endsWith("jpg") }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                InternalStoragePhoto(it.name, bmp)
            } ?: listOf()
        }
    }

    private suspend fun deletePhotoFromInternalStorage(fileName: String) : Boolean {
        return withContext(Dispatchers.IO) {
            try {
                deleteFile(fileName)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun deletePhotoFromExternalStorage(photoUri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                contentResolver.delete(photoUri, null, null)
            } catch (e: SecurityException) {
                val intentSender = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        MediaStore.createDeleteRequest(contentResolver, listOf(photoUri)).intentSender
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        val recoverableSecurityException = e as? RecoverableSecurityException
                        recoverableSecurityException?.userAction?.actionIntent?.intentSender
                    }
                    else -> null
                }
                intentSender?.let {
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(it).build()
                    )
                }
            }
        }
    }

    private fun <T> sdk29OrAbove(onSdk29: () -> T): T? {
        return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onSdk29()
        } else {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
    }
}
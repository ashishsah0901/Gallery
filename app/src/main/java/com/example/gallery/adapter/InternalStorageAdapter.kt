package com.example.gallery.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gallery.data.InternalStoragePhoto
import com.example.gallery.databinding.ItemPhotoBinding

class InternalStorageAdapter(
    private val onPhotoClick: (InternalStoragePhoto) -> Unit
) : ListAdapter<InternalStoragePhoto, InternalStorageAdapter.PhotoViewHolder>(Companion) {

    inner class PhotoViewHolder(val binding: ItemPhotoBinding): RecyclerView.ViewHolder(binding.root)

    companion object : DiffUtil.ItemCallback<InternalStoragePhoto>() {
        override fun areItemsTheSame(oldItem: InternalStoragePhoto, newItem: InternalStoragePhoto): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: InternalStoragePhoto, newItem: InternalStoragePhoto): Boolean {
            return oldItem.name == newItem.name && oldItem.bmp.sameAs(newItem.bmp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        return PhotoViewHolder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = currentList[position]
        holder.binding.apply {
            ivPhoto.setImageBitmap(photo.bmp)

            val aspectRatio = photo.bmp.width.toFloat() / photo.bmp.height.toFloat()
            ConstraintSet().apply {
                clone(root)
                setDimensionRatio(ivPhoto.id, aspectRatio.toString())
                applyTo(root)
            }

            ivPhoto.setOnLongClickListener {
                onPhotoClick(photo)
                true
            }
        }
    }
}
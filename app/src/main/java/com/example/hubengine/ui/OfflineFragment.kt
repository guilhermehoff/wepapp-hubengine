package com.example.hubengine.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.example.hubengine.R

class OfflineFragment : Fragment() {

    private var onRetry: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_offline, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btn_retry).setOnClickListener {
            onRetry?.invoke()
        }
    }

    companion object {
        const val TAG = "offline_fragment"

        fun newInstance(onRetry: () -> Unit): OfflineFragment =
            OfflineFragment().also { it.onRetry = onRetry }
    }
}

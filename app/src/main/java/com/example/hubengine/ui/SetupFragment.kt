package com.example.hubengine.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.example.hubengine.R

class SetupFragment : Fragment() {

    private var onUrlConfirmed: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etUrl = view.findViewById<EditText>(R.id.et_url)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm)

        btnConfirm.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                onUrlConfirmed?.invoke(url)
            } else {
                etUrl.error = getString(R.string.setup_url_error)
            }
        }
    }

    companion object {
        const val TAG = "SetupFragment"

        fun newInstance(onUrlConfirmed: (String) -> Unit): SetupFragment =
            SetupFragment().also { it.onUrlConfirmed = onUrlConfirmed }
    }
}

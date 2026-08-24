package com.amr3d.preview.pro

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

/**
 * مركز أدوات التصنيع.
 * Nesting هو الأداة الحالية، و"اطلب" واجهة مستقبلية تعرض وصفها فقط.
 */
class SlicerFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_slicer, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())

        view.findViewById<View>(R.id.nestingToolCard).setOnClickListener {
            (activity as? MainActivity)?.openNesting()
        }
        view.findViewById<View>(R.id.requestToolCard).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.request_dialog_title))
                .setMessage(getString(R.string.request_dialog_message))
                .setPositiveButton(getString(R.string.action_ok), null)
                .show()
        }
        return view
    }

    companion object {
        fun newInstance() = SlicerFragment()
    }
}

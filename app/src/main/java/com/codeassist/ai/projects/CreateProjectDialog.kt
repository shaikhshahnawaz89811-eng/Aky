package com.codeassist.ai.projects

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.codeassist.ai.R
import com.codeassist.ai.data.Project
import com.codeassist.ai.data.Store
import com.codeassist.ai.workspace.WorkspaceActivity

class CreateProjectDialog : DialogFragment() {

    companion object {
        const val RESULT_KEY = "project_saved"
        private const val ARG_PROJECT_ID = "project_id"

        fun newInstance(projectId: String? = null) = CreateProjectDialog().apply {
            arguments = Bundle().apply { putString(ARG_PROJECT_ID, projectId) }
        }
    }

    private var iconIdx = 0
    private var colorIdx = 0
    private val iconViews = mutableListOf<FrameLayout>()
    private val colorViews = mutableListOf<FrameLayout>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = Dialog(requireContext(), R.style.SheetDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return d
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_create_project, container, false)
        Store.init(requireContext())
        v.setBackgroundResource(if (Store.glassEffect) R.drawable.glass_card else R.drawable.glass_card_solid)
        iconViews.clear()
        colorViews.clear()
        val editing = arguments?.getString(ARG_PROJECT_ID)?.let(Store::project)

        val editName = v.findViewById<EditText>(R.id.editName)
        val editDesc = v.findViewById<EditText>(R.id.editDesc)
        val counterName = v.findViewById<TextView>(R.id.counterName)
        val counterDesc = v.findViewById<TextView>(R.id.counterDesc)
        val btnCreate = v.findViewById<View>(R.id.btnCreate)
        val iconRow = v.findViewById<LinearLayout>(R.id.iconRow)
        val colorRow = v.findViewById<LinearLayout>(R.id.colorRow)
        iconIdx = editing?.iconIdx ?: 0
        colorIdx = editing?.colorIdx ?: 0
        editName.setText(editing?.name.orEmpty())
        editDesc.setText(editing?.desc.orEmpty())
        v.findViewById<TextView>(R.id.textDialogTitle).text =
            if (editing == null) "Create Project" else "Edit Project"
        v.findViewById<TextView>(R.id.textDialogSubtitle).text =
            if (editing == null) "Create a local workspace for your work" else "Update the workspace details"
        (btnCreate as TextView).text = if (editing == null) "Create Project" else "Save Changes"

        v.findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }

        // Icon picker
        ProjectsFragment.ICONS.forEachIndexed { i, res ->
            val box = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
                setBackgroundResource(R.drawable.icon_box)
                val iv = ImageView(context)
                val lp = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                iv.layoutParams = lp
                iv.setImageResource(res)
                iv.setColorFilter(0xFF93A1B3.toInt())
                addView(iv)
                tag = iv
                setOnClickListener {
                    iconIdx = i
                    refreshPickers()
                }
            }
            iconViews.add(box)
            iconRow.addView(box)
        }

        // Color picker
        ProjectsFragment.COLORS.forEachIndexed { i, color ->
            val box = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
                val dot = View(context)
                val lp = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                dot.layoutParams = lp
                dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                addView(dot)
                setOnClickListener {
                    colorIdx = i
                    refreshPickers()
                }
            }
            colorViews.add(box)
            colorRow.addView(box)
        }
        refreshPickers()

        editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                counterName.text = "$len/50"
                btnCreate.isEnabled = !s.isNullOrBlank()
                btnCreate.alpha = if (btnCreate.isEnabled) 1f else 0.5f
            }
        })
        editDesc.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                counterDesc.text = "${s?.length ?: 0}/250"
            }
        })
        btnCreate.isEnabled = editName.text.isNotBlank()
        btnCreate.alpha = if (btnCreate.isEnabled) 1f else 0.5f
        counterName.text = "${editName.text.length}/50"
        counterDesc.text = "${editDesc.text.length}/250"

        btnCreate.setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                editName.error = "Project name is required"
                editName.requestFocus()
                return@setOnClickListener
            }
            val p = editing ?: Project(name = name)
            p.name = name
            p.desc = editDesc.text.toString().trim()
            p.iconIdx = iconIdx
            p.colorIdx = colorIdx
            if (editing == null) Store.addProject(p) else Store.updateProject(p)
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                Bundle().apply {
                    putString(ARG_PROJECT_ID, p.id)
                    putBoolean("created", editing == null)
                }
            )
            dismiss()
            if (editing == null) {
                startActivity(android.content.Intent(requireContext(), WorkspaceActivity::class.java)
                    .putExtra(WorkspaceActivity.EXTRA_PROJECT_ID, p.id))
            }
        }
        return android.widget.ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(v)
        }
    }

    private fun refreshPickers() {
        iconViews.forEachIndexed { i, box ->
            box.setBackgroundResource(if (i == iconIdx) R.drawable.tab_selected else R.drawable.icon_box)
            (box.tag as? ImageView)?.setColorFilter(
                if (i == iconIdx) 0xFF6EC1FF.toInt() else 0xFF93A1B3.toInt()
            )
        }
        colorViews.forEachIndexed { i, box ->
            box.background = if (i == colorIdx) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xFF6EC1FF.toInt())
            } else null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.86f).toInt()
            )
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

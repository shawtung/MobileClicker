package com.shawtung.mobileclicker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ModuleSelectActivity : AppCompatActivity() {

    data class Module(
        val name: String,
        val description: String,
        val activityClass: Class<*>,
        val targetPackage: String  // Target app package name for window detection
    )

    private val modules = listOf(
        Module("自动店长特供1-9", "需要娜娜莉、薄荷、海月",
            MainActivity::class.java, "com.hottagames.yh.laohu"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_module_select)

        val recyclerView = findViewById<RecyclerView>(R.id.moduleList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ModuleAdapter(modules) { module ->
            startActivity(Intent(this, module.activityClass).apply {
                putExtra("targetPackage", module.targetPackage)
            })
        }
    }

    private class ModuleAdapter(
        private val modules: List<Module>,
        private val onClick: (Module) -> Unit
    ) : RecyclerView.Adapter<ModuleAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.moduleName)
            val desc: TextView = view.findViewById(R.id.moduleDesc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_module, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val module = modules[position]
            holder.name.text = module.name
            holder.desc.text = module.description
            holder.itemView.setOnClickListener { onClick(module) }
        }

        override fun getItemCount() = modules.size
    }
}

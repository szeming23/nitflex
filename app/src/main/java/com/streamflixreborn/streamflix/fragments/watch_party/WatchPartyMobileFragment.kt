package com.streamflixreborn.streamflix.fragments.watch_party

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentWatchPartyMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemWatchPartyMessageBinding
import com.streamflixreborn.streamflix.utils.WatchPartyManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class WatchPartyMobileFragment : Fragment() {

    private var _binding: FragmentWatchPartyMobileBinding? = null
    private val binding get() = _binding!!

    private val chatAdapter = ChatAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWatchPartyMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext()).also { it.stackFromEnd = true }
            adapter = chatAdapter
        }

        binding.btnCreateRoom.setOnClickListener { createRoom() }
        binding.btnJoinRoom.setOnClickListener { joinRoom() }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        binding.btnLeave.setOnClickListener {
            WatchPartyManager.leaveRoom()
        }

        binding.tvRoomCode.setOnClickListener {
            val code = WatchPartyManager.roomCode.value ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Room Code", code))
            Toast.makeText(requireContext(), getString(R.string.watch_party_code_copied), Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                WatchPartyManager.inParty,
                WatchPartyManager.connecting,
                WatchPartyManager.connectError,
                WatchPartyManager.roomCode,
                WatchPartyManager.members,
                WatchPartyManager.syncStatus,
            ) { arr ->
                val inParty = arr[0] as Boolean
                val connecting = arr[1] as Boolean
                val error = arr[2] as String?
                val code = arr[3] as String?
                val members = arr[4] as List<*>
                val sync = arr[5] as WatchPartyManager.SyncStatus
                Triple(inParty, connecting, Triple(error, code, Pair(members, sync)))
            }.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { (inParty, connecting, rest) ->
                    val (error, code, membersSync) = rest
                    val (members, sync) = membersSync

                    binding.pbConnecting.isVisible = connecting
                    binding.llLobby.isVisible = !inParty && !connecting
                    binding.llParty.isVisible = inParty

                    binding.tvConnectError.isVisible = error != null
                    binding.tvConnectError.text = error

                    if (inParty && code != null) {
                        binding.tvRoomCode.text = getString(R.string.watch_party_code_label, code)
                        @Suppress("UNCHECKED_CAST")
                        val memberList = members as List<WatchPartyManager.Member>
                        binding.tvMembers.text = memberList.joinToString(" · ") { m ->
                            if (m.isHost) "👑 ${m.name}" else m.name
                        }
                        val syncLabel = if (sync == WatchPartyManager.SyncStatus.SYNCING)
                            getString(R.string.watch_party_syncing)
                        else
                            getString(R.string.watch_party_synced)
                        binding.tvSyncStatus.text = syncLabel
                    }
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            WatchPartyManager.chatMessages
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { messages ->
                    chatAdapter.submitList(messages.toList()) {
                        if (messages.isNotEmpty()) {
                            binding.rvChat.scrollToPosition(messages.size - 1)
                        }
                    }
                }
        }
    }

    private fun createRoom() {
        val name = binding.etDisplayName.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            binding.etDisplayName.error = getString(R.string.watch_party_name_required)
            return
        }
        WatchPartyManager.createRoom(name)
    }

    private fun joinRoom() {
        val name = binding.etDisplayName.text?.toString()?.trim()
        val code = binding.etRoomCode.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            binding.etDisplayName.error = getString(R.string.watch_party_name_required)
            return
        }
        if (code.isNullOrBlank()) {
            binding.etRoomCode.error = getString(R.string.watch_party_code_required)
            return
        }
        WatchPartyManager.joinRoom(name, code)
    }

    private fun sendMessage() {
        val msg = binding.etMessage.text?.toString()?.trim() ?: return
        if (msg.isBlank()) return
        WatchPartyManager.sendChat(msg)
        binding.etMessage.text?.clear()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── Chat adapter ──────────────────────────────────────────────────────────

    private class ChatAdapter : ListAdapter<WatchPartyManager.ChatMessage, RecyclerView.ViewHolder>(DIFF) {

        companion object {
            private const val TYPE_CHAT = 0
            private const val TYPE_SYSTEM = 1

            private val DIFF = object : DiffUtil.ItemCallback<WatchPartyManager.ChatMessage>() {
                override fun areItemsTheSame(a: WatchPartyManager.ChatMessage, b: WatchPartyManager.ChatMessage) = a == b
                override fun areContentsTheSame(a: WatchPartyManager.ChatMessage, b: WatchPartyManager.ChatMessage) = a == b
            }
        }

        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is WatchPartyManager.ChatMessage.Chat -> TYPE_CHAT
            is WatchPartyManager.ChatMessage.System -> TYPE_SYSTEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val binding = ItemWatchPartyMessageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MessageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as MessageViewHolder).bind(getItem(position))
        }

        private class MessageViewHolder(
            private val binding: ItemWatchPartyMessageBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(msg: WatchPartyManager.ChatMessage) {
                when (msg) {
                    is WatchPartyManager.ChatMessage.Chat -> {
                        binding.tvSender.isVisible = true
                        binding.tvSender.text = msg.name
                        binding.tvMessage.text = msg.message
                        binding.tvMessage.alpha = 1f
                    }
                    is WatchPartyManager.ChatMessage.System -> {
                        binding.tvSender.isVisible = false
                        binding.tvMessage.text = msg.message
                        binding.tvMessage.alpha = 0.6f
                    }
                }
            }
        }
    }
}

package com.ultragol.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.ultragol.app.R;
import com.ultragol.app.models.TvChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Header (position 0 — the embedded live player, Categoría/Favoritos tabs and
 * category chips, all bound externally via HeaderBinder) followed by a plain
 * vertical list of channel rows, or an empty placeholder when there's
 * nothing to show for the current filter.
 */
public class TvAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER  = 0;
    public static final int TYPE_CHANNEL = 1;
    public static final int TYPE_EMPTY   = 2;

    public interface HeaderBinder { void bind(View header); }
    public interface OnChannelClickListener { void onChannelClick(TvChannel ch); }

    private final Context context;
    private final HeaderBinder headerBinder;
    private final List<TvChannel> channels = new ArrayList<>();
    private String currentUrl = "";

    private OnChannelClickListener clickListener;

    public TvAdapter(Context ctx, HeaderBinder headerBinder) {
        this.context = ctx;
        this.headerBinder = headerBinder;
    }

    public void setOnChannelClickListener(OnChannelClickListener l) { this.clickListener = l; }

    public void setChannels(List<TvChannel> list) {
        channels.clear();
        if (list != null) channels.addAll(list);
        notifyDataSetChanged();
    }

    /** Highlights the row for the channel currently loaded in the embedded player. */
    public void setCurrentUrl(String url) {
        this.currentUrl = url == null ? "" : url;
        notifyDataSetChanged();
    }

    @Override public int getItemCount() {
        return 1 + (channels.isEmpty() ? 1 : channels.size());
    }

    @Override public int getItemViewType(int pos) {
        if (pos == 0) return TYPE_HEADER;
        return channels.isEmpty() ? TYPE_EMPTY : TYPE_CHANNEL;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater li = LayoutInflater.from(context);
        switch (viewType) {
            case TYPE_HEADER: return new HeaderVH(li.inflate(R.layout.item_tv_header, parent, false));
            case TYPE_EMPTY:  return new EmptyVH(li.inflate(R.layout.item_tv_empty, parent, false));
            default:          return new ChannelVH(li.inflate(R.layout.item_tv_channel, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        if (holder instanceof HeaderVH) {
            headerBinder.bind(holder.itemView);
        } else if (holder instanceof ChannelVH) {
            TvChannel ch = channels.get(pos - 1);
            ((ChannelVH) holder).bind(ch, pos, ch.url.equals(currentUrl));
        }
    }

    class ChannelVH extends RecyclerView.ViewHolder {
        private final ImageView ivLogo;
        private final TextView  tvNumber, tvName, tvStatus;
        private final View      btnGo;

        ChannelVH(View v) {
            super(v);
            ivLogo   = v.findViewById(R.id.ivChannelLogo);
            tvNumber = v.findViewById(R.id.tvChannelNumber);
            tvName   = v.findViewById(R.id.tvChannelName);
            tvStatus = v.findViewById(R.id.tvChannelStatus);
            btnGo    = v.findViewById(R.id.btnChannelGo);
        }

        void bind(TvChannel ch, int number, boolean playing) {
            if (tvNumber != null) tvNumber.setText(String.format("%03d", number));
            if (tvName   != null) tvName.setText(ch.name);
            if (tvStatus != null) tvStatus.setText(playing ? "Reproduciendo ahora" : ch.category);

            int fg  = playing ? 0xFFB794FF : 0xFFFFFFFF;
            int sub = playing ? 0xFFB794FF : 0xFF8A8296;
            if (tvNumber != null) tvNumber.setTextColor(fg);
            if (tvName   != null) tvName.setTextColor(fg);
            if (tvStatus != null) tvStatus.setTextColor(sub);

            if (ivLogo != null) {
                Glide.with(context)
                    .load(ch.logo)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.actionplay_placeholder)
                    .error(R.drawable.actionplay_placeholder)
                    .into(ivLogo);
            }

            itemView.setOnClickListener(v -> { if (clickListener != null) clickListener.onChannelClick(ch); });
            if (btnGo != null) btnGo.setOnClickListener(v -> { if (clickListener != null) clickListener.onChannelClick(ch); });
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder { HeaderVH(View v) { super(v); } }
    static class EmptyVH extends RecyclerView.ViewHolder { EmptyVH(View v) { super(v); } }
}

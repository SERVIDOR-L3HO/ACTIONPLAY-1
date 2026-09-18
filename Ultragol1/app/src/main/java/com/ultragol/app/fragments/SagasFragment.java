package com.ultragol.app.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.ultragol.app.R;
import com.ultragol.app.TvHelper;
import com.ultragol.app.adapters.ContentGridAdapter;
import com.ultragol.app.models.ContentItem;
import com.ultragol.app.network.TmdbApi;
import java.util.*;
import java.util.concurrent.*;

/**
 * "Sagas Completas" — franquicias descubiertas dinámicamente entre lo popular
 * en TMDB (ver TmdbApi.fetchFeaturedSagas), nunca una lista fija. Cada
 * tarjeta abre la primera película de esa saga, cuya ficha ya muestra el
 * resto de la colección completa.
 */
public class SagasFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup p, @Nullable Bundle s) {
        return i.inflate(R.layout.fragment_grid, p, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle s) {
        super.onViewCreated(view, s);

        View backBtn = view.findViewById(R.id.gridBack);
        if (backBtn != null) backBtn.setOnClickListener(v -> requireActivity().onBackPressed());

        TextView title = view.findViewById(R.id.gridTitle);
        if (title != null) title.setText("🎬 Sagas Completas");

        RecyclerView grid = view.findViewById(R.id.contentGrid);
        ProgressBar pb    = view.findViewById(R.id.gridLoading);

        List<ContentItem> items = new ArrayList<>();
        ContentGridAdapter adapter = new ContentGridAdapter(requireContext(), items);
        int cols = getResources().getInteger(R.integer.content_grid_columns);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), cols));
        grid.setAdapter(adapter);
        TvHelper.makeFocusable(grid);

        if (pb != null) pb.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            List<ContentItem> result = TmdbApi.fetchFeaturedSagas();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                items.addAll(result);
                adapter.notifyDataSetChanged();
                if (pb != null) pb.setVisibility(View.GONE);
            });
        });
    }
}

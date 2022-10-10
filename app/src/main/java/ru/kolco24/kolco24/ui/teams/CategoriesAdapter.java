package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.Arrays;
import java.util.List;

public class CategoriesAdapter extends FragmentStateAdapter {
    private static final List<String> caterories = Arrays.asList("6ч", "12ч", "12ч МЖ", "12ч ММ", "12ч ЖЖ", "24ч");

    public CategoriesAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment = new DemoObjectFragment();
        Bundle args = new Bundle();
        args.putString(DemoObjectFragment.ARG_PARAM1, getCategoryName(position));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getItemCount() {
        return caterories.size();
    }


    public static String getCategoryName(int position) {
        if (position < 0 || position >= caterories.size()) {
            return "";
        }
        return caterories.get(position);
    }
}

package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.Arrays;
import java.util.List;

public class CategoriesAdapter extends FragmentStateAdapter {
    private static final List<String> categorie_name = Arrays.asList("6ч", "12", "МЖ", "ММ", "ЖЖ", "24", "8ч", "8ч");
    private static final List<Integer> categorie_code = Arrays.asList(8, 9, 10, 11, 12, 13, 14, 15);
    public CategoriesAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment = new TeamsCategoryFragment();
        Bundle args = new Bundle();
        args.putString(TeamsCategoryFragment.CATEGORY_NAME, getCategoryName(position));
        args.putInt(TeamsCategoryFragment.CATEGORY_CODE, getCategoryCode(position));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getItemCount() {
        return categorie_name.size();
    }


    public static String getCategoryName(int position) {
        if (position < 0 || position >= categorie_name.size()) {
            return "";
        }
        return categorie_name.get(position);
    }

    public static Integer getCategoryCode(int position){
        if (position < 0 || position >= categorie_code.size()) {
            return 0;
        }
        return categorie_code.get(position);
    }
}

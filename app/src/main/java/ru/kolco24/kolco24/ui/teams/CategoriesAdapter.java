package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.Arrays;
import java.util.List;

public class CategoriesAdapter extends FragmentStateAdapter {
    private static final List<String> categorie_name = Arrays.asList("Команды", "Связки");
    private static final List<String> categorie_code = Arrays.asList("gastello_4", "gastello_2");
    public CategoriesAdapter(@NonNull Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment = new DemoObjectFragment();
        Bundle args = new Bundle();
        args.putString(DemoObjectFragment.CATEGORY_NAME, getCategoryName(position));
        args.putString(DemoObjectFragment.CATEGORY_CODE, getCategoryCode(position));
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

    public static String getCategoryCode(int position){
        if (position < 0 || position >= categorie_code.size()) {
            return "";
        }
        return categorie_code.get(position);
    }
}

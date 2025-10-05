package ru.kolco24.kolco24.ui.teams;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import ru.kolco24.kolco24.data.CategoryConfig;

public class CategoriesAdapter extends FragmentStateAdapter {
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
        return CategoryConfig.getCount();
    }


    public static String getCategoryName(int position) {
        return CategoryConfig.getName(position);
    }

    public static Integer getCategoryCode(int position){
        return CategoryConfig.getCode(position);
    }
}

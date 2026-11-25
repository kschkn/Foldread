package ru.foldread;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

public final class WeeklyGoalActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setTitle(R.string.widget_goal_edit);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(20*getResources().getDisplayMetrics().density);
        form.setPadding(padding,padding/2,padding,padding/2);
        EditText minutes=new EditText(this);
        minutes.setSingleLine();minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setId(R.id.widget_week_total);minutes.setText(String.valueOf(ReadingWidgets.weeklyGoal(this)));
        minutes.setContentDescription(getString(R.string.widget_goal_edit));minutes.setSelectAllOnFocus(true);form.addView(minutes);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.END);form.addView(actions);
        Button cancel=new Button(this);cancel.setText(android.R.string.cancel);cancel.setOnClickListener(v->finish());actions.addView(cancel);
        Button save=new Button(this);save.setText(R.string.widget_save);
        save.setOnClickListener(v->{
            int goal;
            try{goal=Integer.parseInt(minutes.getText().toString());}catch(NumberFormatException ex){goal=0;}
            if(goal<1||goal>10080){minutes.setError(getString(R.string.widget_goal_range));return;}
            getSharedPreferences("audio-reader",MODE_PRIVATE).edit().putInt("weeklyGoal",goal).apply();
            ReadingWidgets.refresh(this,ReadingWidgets.Week.class);finish();
        });
        actions.addView(save);setContentView(form);
    }
}

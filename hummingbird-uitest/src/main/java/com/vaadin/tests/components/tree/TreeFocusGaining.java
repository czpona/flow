package com.vaadin.tests.components.tree;

import com.vaadin.data.Property;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.event.ItemClickEvent;
import com.vaadin.tests.components.TestBase;
import com.vaadin.tests.util.Log;
import com.vaadin.ui.TextField;
import com.vaadin.ui.Tree;

@SuppressWarnings("serial")
public class TreeFocusGaining extends TestBase {

    @Override
    protected void setup() {
        final Log log = new Log(5);

        TextField textField = new TextField(
                "My value should get to server when tree is clicked");
        add(textField);
        textField.addValueChangeListener(new Property.ValueChangeListener() {

            @Override
            public void valueChange(ValueChangeEvent event) {
                log.log("TF value now:" + event.getProperty().getValue());
            }
        });

        Tree tree = new Tree("Simple selectable tree (immediate)");
        tree.addItem("Item1");
        add(tree);
        tree.addValueChangeListener(new Property.ValueChangeListener() {

            @Override
            public void valueChange(ValueChangeEvent event) {
                log.log("Tree value now:" + event.getProperty().getValue());
            }
        });

        tree = new Tree("Simple tree with itemm click listener");
        tree.addItem("Item1");
        tree.addItemClickListener(new ItemClickEvent.ItemClickListener() {
            @Override
            public void itemClick(ItemClickEvent event) {
                log.log("Item click event");
            }
        });
        add(tree);

        add(log);

    }

    @Override
    protected String getTestDescription() {
        return "Tree should get focus before sending variables to server.";
    }

    @Override
    protected Integer getTicketNumber() {
        return 6374;
    }

}

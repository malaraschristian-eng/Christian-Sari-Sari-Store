import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Sari-Sari Store Inventory System (desktop, Java Swing)
 *
 * Tab 1  Products       - add, update, delete, restock, search, low-stock alert
 * Tab 2  Sell           - record a sale, computes total and change, reduces stock
 * Tab 3  Sales History  - list of sales with total (today or all)
 *
 * Tab 4  Admin          - (Admin only) accounts, who logged in, activity log
 *
 * Every account has its OWN products and sales (products_<user>.csv, sales_<user>.csv).
 * No database is used.
 */
public class SariSariStore extends JFrame {

    // ---------- Constants ----------
    static final String CUR = "\u20B1";                         // Peso sign
    static final int LOW_STOCK = 10;                            // stock at or below this = LOW
    static final String[] CATEGORIES = {"Canned Goods", "Noodles", "Drinks", "Snacks",
            "Toiletries", "Condiments", "Others"};
    static final String PRODUCTS_FILE = "products.csv";
    static final String SALES_FILE = "sales.csv";
    static final Color GREEN = new Color(27, 120, 70);
    static final Color RED = new Color(200, 35, 35);
    static final Color LIGHT = new Color(243, 247, 244);
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ---------- Data classes ----------
    static class Product {
        String code, name, category;
        double price;
        int stock;

        String toCsv() {
            return esc(code) + "," + esc(name) + "," + esc(category) + "," + price + "," + stock;
        }
    }

    static class Sale {
        String dateTime, name;
        int qty;
        double total;

        String toCsv() {
            return esc(dateTime) + "," + esc(name) + "," + qty + "," + total;
        }
    }

    // ---------- Fields ----------
    private final List<Product> products = new ArrayList<>();
    private final List<Sale> sales = new ArrayList<>();
    private final List<Product> shown = new ArrayList<>();     // products currently in the table
    private Product selected = null;                           // product picked in the table
    private boolean refreshing = false;
    private final String currentUser, currentRole;

    private JLabel lblStatus;

    // Products tab
    private JTextField tName, tPrice, tStock, tSearch;
    private JComboBox<String> cbCategory;
    private JTable productTable;
    private DefaultTableModel productModel;
    private JLabel lblSummary;

    // Sell tab
    private JComboBox<String> cbProduct;
    private JTextField tQty, tPaid;
    private JLabel lblPrice, lblAvail, lblTotal, lblChange, lblToday;

    // Sales tab
    private JComboBox<String> cbRange;
    private DefaultTableModel salesModel;
    private JLabel lblSalesSummary;

    // Admin tab
    private DefaultTableModel accountsModel, logModel;
    private JTable adminTable;
    private JLabel lblAdminSummary;

    // ======================================================================
    //  Constructor / main layout
    // ======================================================================
    public SariSariStore(String username, String role) {
        this.currentUser = username;
        this.currentRole = role;
        setTitle("Christian Sari-Sari Store Inventory - " + username + " (" + role + ")");
        setSize(920, 640);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Title bar
        JPanel title = new JPanel(new BorderLayout());
        title.setBackground(GREEN);
        title.setBorder(new EmptyBorder(12, 20, 12, 20));
        JLabel t = new JLabel("Sari-Sari Store Inventory ");
        t.setForeground(Color.WHITE);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
        title.add(t, BorderLayout.WEST);

        JPanel userBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        userBox.setOpaque(false);
        JLabel lblUser = new JLabel("Logged in: " + currentUser + " (" + currentRole + ")");
        lblUser.setForeground(Color.WHITE);
        JButton btnLogout = new JButton("Log out");
        btnLogout.addActionListener(e -> logout());
        userBox.add(lblUser);
        userBox.add(btnLogout);
        title.add(userBox, BorderLayout.EAST);
        add(title, BorderLayout.NORTH);

        // Tabs
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.addTab("Products", buildProductsTab());
        tabs.addTab("Sell", buildSellTab());
        tabs.addTab("Sales History", buildSalesTab());
        if (currentRole.equals("Admin")) tabs.addTab("Admin", buildAdminTab());
        tabs.addChangeListener(e -> refreshAll());
        add(tabs, BorderLayout.CENTER);

        // Status bar
        lblStatus = new JLabel(" Ready.");
        lblStatus.setBorder(BorderFactory.createEtchedBorder());
        add(lblStatus, BorderLayout.SOUTH);

        loadData();
        refreshAll();
    }

    // ======================================================================
    //  TAB 1: Products
    // ======================================================================
    private JPanel buildProductsTab() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 15, 10, 15));

        // ----- Form -----
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Product Details"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 8, 5, 8);
        g.fill = GridBagConstraints.HORIZONTAL;

        tName = new JTextField(16);
        cbCategory = new JComboBox<>(CATEGORIES);
        tPrice = new JTextField(10);
        tStock = new JTextField(10);

        addField(form, g, 0, 0, "Product name:", tName);
        addField(form, g, 0, 2, "Category:", cbCategory);
        addField(form, g, 1, 0, "Price (" + CUR + "):", tPrice);
        addField(form, g, 1, 2, "Stock:", tStock);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton bAdd = new JButton("Add");
        JButton bUpdate = new JButton("Update");
        JButton bDelete = new JButton("Delete");
        JButton bRestock = new JButton("Restock");
        JButton bClear = new JButton("Clear");
        bAdd.addActionListener(e -> addProduct());
        bUpdate.addActionListener(e -> updateProduct());
        bDelete.addActionListener(e -> deleteProduct());
        bRestock.addActionListener(e -> restockProduct());
        bClear.addActionListener(e -> clearForm());
        for (JButton b : new JButton[]{bAdd, bUpdate, bDelete, bRestock, bClear}) buttons.add(b);
        g.gridx = 0;
        g.gridy = 2;
        g.gridwidth = 4;
        form.add(buttons, g);

        root.add(form, BorderLayout.NORTH);

        // ----- Search + table -----
        JPanel center = new JPanel(new BorderLayout(0, 6));
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        tSearch = new JTextField(20);
        onType(tSearch, this::refreshProducts);                // filters while typing
        lblSummary = new JLabel(" ");
        searchRow.add(new JLabel("Search:"));
        searchRow.add(tSearch);
        searchRow.add(Box.createHorizontalStrut(20));
        searchRow.add(lblSummary);
        center.add(searchRow, BorderLayout.NORTH);

        productModel = readOnlyModel("Code", "Product", "Category", "Price", "Stock", "Status");
        productTable = new JTable(productModel);
        styleTable(productTable);
        productTable.setDefaultRenderer(Object.class, new ProductRenderer());
        productTable.getColumnModel().getColumn(0).setMaxWidth(70);
        productTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = productTable.getSelectedRow();
            if (r < 0 || r >= shown.size()) return;
            selected = shown.get(r);
            tName.setText(selected.name);
            cbCategory.setSelectedItem(selected.category);
            tPrice.setText(String.valueOf(selected.price));
            tStock.setText(String.valueOf(selected.stock));
        });
        center.add(new JScrollPane(productTable), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JLabel hint = new JLabel("Red rows = stock is " + LOW_STOCK + " or less. Click a row to edit it.");
        hint.setForeground(Color.GRAY);
        root.add(hint, BorderLayout.SOUTH);
        return root;
    }

    /** Colors low-stock rows red. */
    private class ProductRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel,
                                                       boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, sel, focus, row, col);
            setHorizontalAlignment(col == 3 || col == 4 ? RIGHT : LEFT);
            if (!sel && row < shown.size()) {
                setForeground(shown.get(row).stock <= LOW_STOCK ? RED : Color.BLACK);
            }
            return c;
        }
    }

    private void addProduct() {
        Product p = readProductForm(null);
        if (p == null) return;
        p.code = nextCode();
        products.add(p);
        saveAll();
        refreshAll();
        clearForm();
        setStatus("Product added: " + p.name);
    }

    private void updateProduct() {
        if (selected == null) {
            showError("Please click a product in the table first.");
            return;
        }
        Product p = readProductForm(selected);
        if (p == null) return;
        selected.name = p.name;
        selected.category = p.category;
        selected.price = p.price;
        selected.stock = p.stock;
        saveAll();
        refreshAll();
        clearForm();
        setStatus("Product updated.");
    }

    private void logout() {
        int ok = JOptionPane.showConfirmDialog(this, "Log out of the system?", "Log out", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        StoreUserStore.log(currentUser, "Logout", "");
        StoreLoginFrame.open();
        dispose();
    }

    private void deleteProduct() {
        if (selected == null) {
            showError("Please click a product in the table first.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Delete \"" + selected.name + "\"?",
                "Confirm delete", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        products.remove(selected);
        saveAll();
        refreshAll();
        clearForm();
        setStatus("Product deleted.");
    }

    private void restockProduct() {
        if (selected == null) {
            showError("Please click a product in the table first.");
            return;
        }
        String input = JOptionPane.showInputDialog(this, "How many pieces to add to \"" + selected.name + "\"?",
                "Restock", JOptionPane.QUESTION_MESSAGE);
        if (input == null) return;
        try {
            int qty = Integer.parseInt(input.trim());
            if (qty <= 0) {
                showError("Quantity must be greater than zero.");
                return;
            }
            selected.stock += qty;
            saveAll();
            refreshAll();
            setStatus("Restocked " + selected.name + " (+" + qty + ").");
            tStock.setText(String.valueOf(selected.stock));
        } catch (NumberFormatException ex) {
            showError("Please enter a whole number.");
        }
    }

    /** Reads and validates the form. 'current' is the product being edited (null when adding). */
    private Product readProductForm(Product current) {
        String name = tName.getText().trim();
        if (name.isEmpty()) {
            showError("Product name is required.");
            return null;
        }
        for (Product p : products) {
            if (p != current && p.name.equalsIgnoreCase(name)) {
                showError("A product with this name already exists.");
                return null;
            }
        }
        double price;
        int stock;
        try {
            price = Double.parseDouble(tPrice.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Price must be a number.");
            return null;
        }
        try {
            stock = Integer.parseInt(tStock.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Stock must be a whole number.");
            return null;
        }
        if (price <= 0) {
            showError("Price must be greater than zero.");
            return null;
        }
        if (stock < 0) {
            showError("Stock cannot be negative.");
            return null;
        }
        Product p = new Product();
        p.name = name;
        p.category = (String) cbCategory.getSelectedItem();
        p.price = price;
        p.stock = stock;
        return p;
    }

    private void clearForm() {
        selected = null;
        tName.setText("");
        tPrice.setText("");
        tStock.setText("");
        cbCategory.setSelectedIndex(0);
        productTable.clearSelection();
    }

    private String nextCode() {
        int max = 0;
        for (Product p : products) {
            try {
                max = Math.max(max, Integer.parseInt(p.code.substring(1)));
            } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
                // skip codes that are not in the P001 format
            }
        }
        return String.format("P%03d", max + 1);
    }

    // ======================================================================
    //  TAB 2: Sell
    // ======================================================================
    private JPanel buildSellTab() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(20, 40, 20, 40));

        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createTitledBorder("New Sale"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8, 10, 8, 10);
        g.fill = GridBagConstraints.HORIZONTAL;

        cbProduct = new JComboBox<>();
        cbProduct.addActionListener(e -> updateSellInfo());
        tQty = new JTextField(8);
        tPaid = new JTextField(10);
        lblPrice = new JLabel("-");
        lblAvail = new JLabel("-");
        lblTotal = new JLabel(CUR + "0.00");
        lblTotal.setFont(lblTotal.getFont().deriveFont(Font.BOLD, 26f));
        lblTotal.setForeground(GREEN);
        lblChange = new JLabel(CUR + "0.00");
        lblChange.setFont(lblChange.getFont().deriveFont(Font.BOLD, 20f));
        onType(tQty, this::updateSellInfo);
        onType(tPaid, this::updateSellInfo);

        addField(card, g, 0, 0, "Product:", cbProduct);
        addField(card, g, 1, 0, "Price each:", lblPrice);
        addField(card, g, 2, 0, "Stock left:", lblAvail);
        addField(card, g, 3, 0, "Quantity:", tQty);
        addField(card, g, 4, 0, "TOTAL:", lblTotal);
        addField(card, g, 5, 0, "Amount paid (" + CUR + "):", tPaid);
        addField(card, g, 6, 0, "CHANGE:", lblChange);

        JButton bSell = new JButton("Complete Sale");
        bSell.setBackground(GREEN);
        bSell.setForeground(Color.WHITE);
        bSell.setOpaque(true);
        bSell.setBorderPainted(false);
        bSell.setFont(bSell.getFont().deriveFont(Font.BOLD, 15f));
        bSell.setPreferredSize(new Dimension(100, 40));
        bSell.addActionListener(e -> completeSale());
        g.gridx = 0;
        g.gridy = 7;
        g.gridwidth = 2;
        card.add(bSell, g);

        root.add(card, BorderLayout.NORTH);

        lblToday = new JLabel(" ");
        lblToday.setFont(lblToday.getFont().deriveFont(Font.BOLD, 16f));
        lblToday.setBorder(new EmptyBorder(15, 5, 0, 0));
        root.add(lblToday, BorderLayout.CENTER);
        return root;
    }

    private Product sellProduct() {
        int i = cbProduct.getSelectedIndex();
        return (i >= 0 && i < products.size()) ? products.get(i) : null;
    }

    /** Updates price, stock, total and change while the user types. */
    private void updateSellInfo() {
        if (refreshing) return;
        Product p = sellProduct();
        if (p == null) {
            lblPrice.setText("-");
            lblAvail.setText("-");
            lblTotal.setText(CUR + "0.00");
            lblChange.setText(CUR + "0.00");
            return;
        }
        lblPrice.setText(CUR + money(p.price));
        lblAvail.setText(p.stock + " pcs");
        double total = 0;
        try {
            total = p.price * Integer.parseInt(tQty.getText().trim());
        } catch (NumberFormatException ignored) {
            // empty or invalid quantity = total stays 0
        }
        lblTotal.setText(CUR + money(total));
        try {
            double change = Double.parseDouble(tPaid.getText().trim()) - total;
            lblChange.setText(CUR + money(Math.max(0, change)));
        } catch (NumberFormatException ex) {
            lblChange.setText(CUR + "0.00");
        }
    }

    private void completeSale() {
        Product p = sellProduct();
        if (p == null) {
            showError("There are no products to sell. Add products first.");
            return;
        }
        int qty;
        try {
            qty = Integer.parseInt(tQty.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Quantity must be a whole number.");
            return;
        }
        if (qty <= 0) {
            showError("Quantity must be greater than zero.");
            return;
        }
        if (qty > p.stock) {
            showError("Not enough stock. Only " + p.stock + " left.");
            return;
        }
        double total = p.price * qty;
        double paid;
        try {
            paid = Double.parseDouble(tPaid.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Please enter the amount paid.");
            return;
        }
        if (paid < total) {
            showError("Amount paid is less than the total (" + CUR + money(total) + ").");
            return;
        }

        p.stock -= qty;
        Sale s = new Sale();
        s.dateTime = LocalDateTime.now().format(DT);
        s.name = p.name;
        s.qty = qty;
        s.total = total;
        sales.add(s);
        saveAll();

        String msg = "Sale complete!\n\nTotal: " + CUR + money(total) + "\nChange: " + CUR + money(paid - total);
        if (p.stock <= LOW_STOCK) {
            msg += "\n\nWarning: only " + p.stock + " pcs of " + p.name + " left. Time to restock!";
        }
        JOptionPane.showMessageDialog(this, msg, "Sale", JOptionPane.INFORMATION_MESSAGE);

        tQty.setText("");
        tPaid.setText("");
        refreshAll();
        setStatus("Sold " + qty + " x " + p.name + " = " + CUR + money(total));
    }

    // ======================================================================
    //  TAB 3: Sales History
    // ======================================================================
    private JPanel buildSalesTab() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 15, 10, 15));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        cbRange = new JComboBox<>(new String[]{"Today", "All"});
        cbRange.addActionListener(e -> refreshSales());
        lblSalesSummary = new JLabel(" ");
        lblSalesSummary.setFont(lblSalesSummary.getFont().deriveFont(Font.BOLD, 14f));
        top.add(new JLabel("Show:"));
        top.add(cbRange);
        top.add(Box.createHorizontalStrut(20));
        top.add(lblSalesSummary);
        root.add(top, BorderLayout.NORTH);

        salesModel = readOnlyModel("Date & Time", "Product", "Quantity", "Total");
        JTable table = new JTable(salesModel);
        styleTable(table);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        return root;
    }

    // ======================================================================
    //  TAB 4: Admin (only visible to Admin accounts)
    // ======================================================================
    private JPanel buildAdminTab() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 15, 10, 15));

        // Accounts
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        lblAdminSummary = new JLabel(" ");
        lblAdminSummary.setFont(lblAdminSummary.getFont().deriveFont(Font.BOLD, 13f));
        JButton bView = new JButton("View user's store data");
        JButton bRole = new JButton("Change role (Admin / Staff)");
        JButton bDel = new JButton("Delete account");
        JButton bRef = new JButton("Refresh");
        bView.addActionListener(e -> viewUserData());
        bRole.addActionListener(e -> changeRole());
        bDel.addActionListener(e -> deleteAccount());
        bRef.addActionListener(e -> refreshAdmin());
        top.add(bView);
        top.add(bRole);
        top.add(bDel);
        top.add(bRef);
        top.add(Box.createHorizontalStrut(10));
        top.add(lblAdminSummary);

        accountsModel = readOnlyModel("Username", "Role", "Created", "Last login", "Logins", "Products", "Total sales");
        adminTable = new JTable(accountsModel);
        styleTable(adminTable);
        adminTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel accounts = new JPanel(new BorderLayout(0, 4));
        accounts.setBorder(BorderFactory.createTitledBorder("Accounts (who created an account)"));
        accounts.add(top, BorderLayout.NORTH);
        accounts.add(new JScrollPane(adminTable), BorderLayout.CENTER);

        // Activity log
        logModel = readOnlyModel("Date & Time", "User", "Activity", "Details");
        JTable logTable = new JTable(logModel);
        styleTable(logTable);
        JPanel log = new JPanel(new BorderLayout());
        log.setBorder(BorderFactory.createTitledBorder("Activity log (who accessed the system)"));
        log.add(new JScrollPane(logTable), BorderLayout.CENTER);

        JPanel both = new JPanel(new GridLayout(2, 1, 0, 10));
        both.add(accounts);
        both.add(log);
        root.add(both, BorderLayout.CENTER);
        return root;
    }

    private void refreshAdmin() {
        if (accountsModel == null) return;
        accountsModel.setRowCount(0);
        List<StoreUserStore.User> users = StoreUserStore.allUsers();
        for (StoreUserStore.User u : users) {
            accountsModel.addRow(new Object[]{u.name, u.role, u.created.isEmpty() ? "-" : u.created,
                    u.lastLogin.isEmpty() ? "never" : u.lastLogin, u.logins,
                    StoreUserStore.countProducts(u.name), CUR + money(StoreUserStore.totalSales(u.name))});
        }
        logModel.setRowCount(0);
        List<String[]> log = StoreUserStore.readLog();
        for (int i = log.size() - 1; i >= 0; i--) {                    // newest first
            String[] r = log.get(i);
            logModel.addRow(new Object[]{r[0], r[1], r[2], r[3]});
        }
        lblAdminSummary.setText("Accounts: " + users.size() + "   |   Log entries: " + log.size());
    }

    private String selectedAccount() {
        int r = adminTable.getSelectedRow();
        if (r < 0) {
            showError("Please click an account in the Accounts table first.");
            return null;
        }
        return (String) accountsModel.getValueAt(r, 0);
    }

    private void changeRole() {
        String user = selectedAccount();
        if (user == null) return;
        if (user.equalsIgnoreCase(currentUser)) {
            showError("You can't change your own role.");
            return;
        }
        String newRole = StoreUserStore.roleOf(user).equals("Admin") ? "Staff" : "Admin";
        StoreUserStore.setRole(user, newRole);
        StoreUserStore.log(currentUser, "Role changed", user + " is now " + newRole);
        refreshAdmin();
        setStatus(user + " is now " + newRole + ".");
    }

    private void deleteAccount() {
        String user = selectedAccount();
        if (user == null) return;
        if (user.equalsIgnoreCase(currentUser)) {
            showError("You can't delete the account you are using.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Delete the account \"" + user
                + "\"?\nTheir saved store data files stay in the folder.", "Delete account", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        StoreUserStore.deleteUser(user);
        StoreUserStore.log(currentUser, "Account deleted", user);
        refreshAdmin();
        setStatus("Account deleted: " + user);
    }

    /** Read-only window with the products and sales of the selected account. */
    private void viewUserData() {
        String user = selectedAccount();
        if (user == null) return;
        DefaultTableModel pm = readOnlyModel("Code", "Product", "Category", "Price", "Stock");
        for (List<String> f : StoreUserStore.readCsv(StoreUserStore.dataFile(user, "products", false))) {
            if (f.size() >= 5) pm.addRow(new Object[]{f.get(0), f.get(1), f.get(2), CUR + f.get(3), f.get(4)});
        }
        DefaultTableModel sm = readOnlyModel("Date & Time", "Product", "Quantity", "Total");
        List<List<String>> rows = StoreUserStore.readCsv(StoreUserStore.dataFile(user, "sales", false));
        for (int i = rows.size() - 1; i >= 0; i--) {
            List<String> f = rows.get(i);
            if (f.size() >= 4) sm.addRow(new Object[]{f.get(0), f.get(1), f.get(2), CUR + f.get(3)});
        }
        JTable pt = new JTable(pm), st = new JTable(sm);
        styleTable(pt);
        styleTable(st);
        JTabbedPane tp = new JTabbedPane();
        tp.addTab("Products", new JScrollPane(pt));
        tp.addTab("Sales", new JScrollPane(st));
        tp.setPreferredSize(new Dimension(640, 320));
        JOptionPane.showMessageDialog(this, tp, user + "'s store data (view only)", JOptionPane.PLAIN_MESSAGE);
    }

    // ======================================================================
    //  Refresh
    // ======================================================================
    private void refreshAll() {
        if (productModel == null || salesModel == null || cbProduct == null) return;
        refreshProducts();
        refreshSellCombo();
        refreshSales();
        refreshAdmin();
    }

    private void refreshProducts() {
        if (productModel == null) return;
        String key = tSearch.getText().trim().toLowerCase();
        shown.clear();
        productModel.setRowCount(0);
        for (Product p : products) {
            if (!key.isEmpty() && !p.name.toLowerCase().contains(key)
                    && !p.category.toLowerCase().contains(key) && !p.code.toLowerCase().contains(key)) continue;
            shown.add(p);
            String status = p.stock == 0 ? "OUT OF STOCK" : (p.stock <= LOW_STOCK ? "LOW STOCK" : "OK");
            productModel.addRow(new Object[]{p.code, p.name, p.category, CUR + money(p.price), p.stock, status});
        }
        int low = 0;
        for (Product p : products) if (p.stock <= LOW_STOCK) low++;
        lblSummary.setText("Total products: " + products.size() + "   |   Low / out of stock: " + low);
    }

    private void refreshSellCombo() {
        refreshing = true;
        int keep = cbProduct.getSelectedIndex();
        cbProduct.removeAllItems();
        for (Product p : products) {
            cbProduct.addItem(p.name + "  -  " + CUR + money(p.price) + (p.stock == 0 ? "  (OUT OF STOCK)" : ""));
        }
        if (keep >= 0 && keep < products.size()) cbProduct.setSelectedIndex(keep);
        refreshing = false;
        updateSellInfo();
    }

    private void refreshSales() {
        if (salesModel == null) return;
        String today = LocalDate.now().toString();
        boolean onlyToday = cbRange.getSelectedIndex() == 0;
        salesModel.setRowCount(0);
        double sum = 0, todaySum = 0;
        int count = 0;
        for (int i = sales.size() - 1; i >= 0; i--) {                  // newest first
            Sale s = sales.get(i);
            if (s.dateTime.startsWith(today)) todaySum += s.total;
            if (onlyToday && !s.dateTime.startsWith(today)) continue;
            salesModel.addRow(new Object[]{s.dateTime, s.name, s.qty, CUR + money(s.total)});
            sum += s.total;
            count++;
        }
        lblSalesSummary.setText("Transactions: " + count + "     Total sales: " + CUR + money(sum));
        if (lblToday != null) lblToday.setText("Today's sales: " + CUR + money(todaySum));
    }

    // ======================================================================
    //  CSV files
    // ======================================================================
    static String esc(String s) {
        if (s.contains(",") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private void saveAll() {
        try {
            List<String> pl = new ArrayList<>();
            for (Product p : products) pl.add(p.toCsv());
            Files.write(StoreUserStore.dataFile(currentUser, "products", true), pl, StandardCharsets.UTF_8);

            List<String> sl = new ArrayList<>();
            for (Sale s : sales) sl.add(s.toCsv());
            Files.write(StoreUserStore.dataFile(currentUser, "sales", true), sl, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            showError("Could not save data: " + ex.getMessage());
        }
    }

    private void loadData() {
        Path pp = StoreUserStore.dataFile(currentUser, "products", false);
        if (!Files.exists(pp)) {
            seedSampleData();                                           // first run only
            saveAll();
            return;
        }
        try {
            for (String line : Files.readAllLines(pp, StandardCharsets.UTF_8)) {
                List<String> f = splitCsv(line);
                if (f.size() < 5) continue;
                try {
                    Product p = new Product();
                    p.code = f.get(0);
                    p.name = f.get(1);
                    p.category = f.get(2);
                    p.price = Double.parseDouble(f.get(3));
                    p.stock = Integer.parseInt(f.get(4));
                    products.add(p);
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
            Path sp = StoreUserStore.dataFile(currentUser, "sales", false);
            if (Files.exists(sp)) {
                for (String line : Files.readAllLines(sp, StandardCharsets.UTF_8)) {
                    List<String> f = splitCsv(line);
                    if (f.size() < 4) continue;
                    try {
                        Sale s = new Sale();
                        s.dateTime = f.get(0);
                        s.name = f.get(1);
                        s.qty = Integer.parseInt(f.get(2));
                        s.total = Double.parseDouble(f.get(3));
                        sales.add(s);
                    } catch (NumberFormatException ignored) {
                        // skip a damaged line
                    }
                }
            }
        } catch (IOException ex) {
            showError("Could not load data: " + ex.getMessage());
        }
    }

    private void seedSampleData() {
        seed("P001", "Instant Noodles", "Noodles", 15, 40);
        seed("P002", "Softdrink (Mismo)", "Drinks", 20, 24);
        seed("P003", "Chips", "Snacks", 18, 15);
        seed("P004", "Sardines (Can)", "Canned Goods", 25, 8);
        seed("P005", "Bath Soap", "Toiletries", 38, 12);
        seed("P006", "3-in-1 Coffee", "Drinks", 8, 50);
        seed("P007", "Bottled Water", "Drinks", 15, 30);
        seed("P008", "Cooking Oil (sachet)", "Condiments", 12, 6);
    }

    private void seed(String code, String name, String cat, double price, int stock) {
        Product p = new Product();
        p.code = code;
        p.name = name;
        p.category = cat;
        p.price = price;
        p.stock = stock;
        products.add(p);
    }

    // ======================================================================
    //  Small helpers
    // ======================================================================
    private void addField(JPanel p, GridBagConstraints g, int row, int col, String label, JComponent field) {
        g.gridx = col;
        g.gridy = row;
        g.gridwidth = 1;
        g.weightx = 0;
        p.add(new JLabel(label), g);
        g.gridx = col + 1;
        g.weightx = 1;
        p.add(field, g);
    }

    private void onType(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    private DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private void styleTable(JTable t) {
        t.setRowHeight(28);
        t.setFont(t.getFont().deriveFont(14f));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setBackground(LIGHT);
        t.getTableHeader().setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD, 13f));
    }

    private static String money(double v) {
        return String.format("%,.2f", v);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void setStatus(String msg) {
        lblStatus.setText(" " + msg);
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(StoreLoginFrame::open);
    }
}


// ==========================================================================
//  User accounts (stored in store_users.csv, passwords are salted + hashed)
// ==========================================================================
class StoreUserStore {
    private static final String FILE = "store_users.csv";
    private static final String LOG_FILE = "store_activity.csv";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One account. */
    static class User {
        String name, salt, hash, role, created = "", lastLogin = "";
        int logins;

        String toCsv() {
            return name + "," + salt + "," + hash + "," + role + "," + created + "," + lastLogin + "," + logins;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(STAMP);
    }

    /** First run: create the default admin account. */
    static void ensureDefaults() {
        if (Files.exists(Paths.get(FILE))) return;
        List<User> list = new ArrayList<>();
        list.add(newUser("admin", "admin123", "Admin"));
        saveUsers(list);
    }

    static List<User> allUsers() {
        List<User> out = new ArrayList<>();
        try {
            if (Files.exists(Paths.get(FILE))) {
                for (String l : Files.readAllLines(Paths.get(FILE), StandardCharsets.UTF_8)) {
                    if (l.isBlank()) continue;
                    String[] p = l.split(",", -1);
                    if (p.length < 4) continue;
                    User u = new User();
                    u.name = p[0];
                    u.salt = p[1];
                    u.hash = p[2];
                    u.role = p[3];
                    if (p.length >= 5) u.created = p[4];
                    if (p.length >= 6) u.lastLogin = p[5];
                    if (p.length >= 7) {
                        try {
                            u.logins = Integer.parseInt(p[6]);
                        } catch (NumberFormatException ignored) {
                            // keep 0
                        }
                    }
                    out.add(u);
                }
            }
        } catch (IOException ignored) {
            // treated as no accounts
        }
        return out;
    }

    private static boolean saveUsers(List<User> list) {
        List<String> lines = new ArrayList<>();
        for (User u : list) lines.add(u.toCsv());
        try {
            Files.write(Paths.get(FILE), lines, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Returns {username, role} when the login is correct, otherwise null. Both cases are logged. */
    static String[] authenticate(String user, String pass) {
        List<User> list = allUsers();
        for (User u : list) {
            if (u.name.equalsIgnoreCase(user) && u.hash.equals(hash(pass, u.salt))) {
                u.lastLogin = now();
                u.logins++;
                saveUsers(list);
                log(u.name, "Login", "");
                return new String[]{u.name, u.role};
            }
        }
        log(user, "Failed login", "Wrong username or password");
        return null;
    }

    /** Creates a Staff account. Returns an error message, or null when successful. */
    static String register(String user, String pass, String confirm) {
        if (!user.matches("[A-Za-z0-9_]{3,20}")) {
            return "Username must be 3-20 letters, numbers or underscore.";
        }
        if (pass.length() < 6) {
            return "Password must be at least 6 characters.";
        }
        if (!pass.equals(confirm)) {
            return "Passwords do not match.";
        }
        List<User> list = allUsers();
        for (User u : list) {
            if (u.name.equalsIgnoreCase(user)) return "That username is already taken.";
        }
        list.add(newUser(user, pass, "Staff"));
        if (!saveUsers(list)) return "Could not save the account.";
        log(user, "Account created", "New Staff account");
        return null;
    }

    static String roleOf(String user) {
        for (User u : allUsers()) if (u.name.equalsIgnoreCase(user)) return u.role;
        return "Staff";
    }

    static void setRole(String user, String role) {
        List<User> list = allUsers();
        for (User u : list) if (u.name.equalsIgnoreCase(user)) u.role = role;
        saveUsers(list);
    }

    static void deleteUser(String user) {
        List<User> list = allUsers();
        list.removeIf(u -> u.name.equalsIgnoreCase(user));
        saveUsers(list);
    }

    // ---------- Activity log ----------
    static void log(String user, String action, String details) {
        String line = SariSariStore.esc(now()) + "," + SariSariStore.esc(user) + "," + SariSariStore.esc(action)
                + "," + SariSariStore.esc(details);
        try {
            Files.write(Paths.get(LOG_FILE), List.of(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the system keeps working even if the log cannot be written
        }
    }

    static List<String[]> readLog() {
        List<String[]> out = new ArrayList<>();
        for (List<String> f : readCsv(Paths.get(LOG_FILE))) {
            String[] r = {"", "", "", ""};
            for (int i = 0; i < 4 && i < f.size(); i++) r[i] = f.get(i);
            out.add(r);
        }
        return out;
    }

    // ---------- Separate data for every account ----------
    /** products_<user>.csv / sales_<user>.csv. The old products.csv / sales.csv still load for "admin". */
    static Path dataFile(String user, String kind, boolean forSave) {
        Path p = Paths.get(kind + "_" + user.toLowerCase() + ".csv");
        if (!forSave && !Files.exists(p) && user.equalsIgnoreCase("admin")) {
            Path legacy = Paths.get(kind + ".csv");
            if (Files.exists(legacy)) return legacy;
        }
        return p;
    }

    static List<List<String>> readCsv(Path p) {
        List<List<String>> rows = new ArrayList<>();
        try {
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) rows.add(SariSariStore.splitCsv(line));
                }
            }
        } catch (IOException ignored) {
            // treated as empty
        }
        return rows;
    }

    static int countProducts(String user) {
        int n = 0;
        for (List<String> f : readCsv(dataFile(user, "products", false))) if (f.size() >= 5) n++;
        return n;
    }

    static double totalSales(String user) {
        double sum = 0;
        for (List<String> f : readCsv(dataFile(user, "sales", false))) {
            if (f.size() < 4) continue;
            try {
                sum += Double.parseDouble(f.get(3));
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        return sum;
    }

    // ---------- Passwords ----------
    private static User newUser(String name, String pass, String role) {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        User u = new User();
        u.name = name;
        u.salt = Base64.getEncoder().encodeToString(saltBytes);
        u.hash = hash(pass, u.salt);
        u.role = role;
        u.created = now();
        return u;
    }

    private static String hash(String pass, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest((salt + pass).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

// ==========================================================================
//  Picture panel: shows store.jpg / store.png if you put one in the project
//  folder, otherwise draws a sari-sari store illustration.
// ==========================================================================
class StorePicturePanel extends JPanel {
    private BufferedImage photo = null;

    StorePicturePanel() {
        for (String name : new String[]{"store.jpg", "store.jpeg", "store.png"}) {
            File f = new File(name);
            if (f.exists()) {
                try {
                    photo = ImageIO.read(f);
                    if (photo != null) break;
                } catch (IOException ignored) {
                    // fall back to the drawing
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();

        if (photo != null) {                                   // your own picture, scaled to fill
            double sc = Math.max((double) w / photo.getWidth(), (double) h / photo.getHeight());
            int dw = (int) (photo.getWidth() * sc), dh = (int) (photo.getHeight() * sc);
            g.drawImage(photo, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
            g.dispose();
            return;
        }

        // Sky, sun, clouds
        g.setPaint(new GradientPaint(0, 0, new Color(110, 185, 240), 0, (int) (h * 0.75), new Color(225, 243, 255)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 218, 80));
        g.fillOval((int) (w * 0.76), (int) (h * 0.05), (int) (w * 0.15), (int) (w * 0.15));
        g.setColor(Color.WHITE);
        cloud(g, w * 0.08, h * 0.09, w * 0.20);
        cloud(g, w * 0.45, h * 0.04, w * 0.15);

        // Road and sidewalk
        g.setColor(new Color(95, 98, 105));
        g.fillRect(0, (int) (h * 0.88), w, h - (int) (h * 0.88));
        g.setColor(new Color(195, 190, 180));
        g.fillRect(0, (int) (h * 0.83), w, (int) (h * 0.05));

        // Building wall
        int bx = (int) (w * 0.07), by = (int) (h * 0.36), bw = (int) (w * 0.86), bh = (int) (h * 0.47);
        g.setColor(new Color(247, 214, 150));
        g.fillRect(bx, by, bw, bh);

        // Corrugated roof
        int ry = by - (int) (h * 0.07), rh = (int) (h * 0.07), over = (int) (w * 0.02);
        g.setColor(new Color(120, 126, 136));
        g.fillRect(bx - over, ry, bw + 2 * over, rh);
        g.setColor(new Color(90, 95, 105));
        for (int x = bx - over; x < bx + bw + over; x += Math.max(6, w / 45)) g.drawLine(x, ry, x, ry + rh);

        // Sign
        int sw = (int) (w * 0.56), sh = (int) (h * 0.085), sx = bx + (bw - sw) / 2, sy = by + (int) (h * 0.02);
        g.setColor(new Color(200, 35, 35));
        g.fillRoundRect(sx, sy, sw, sh, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(11, (int) (w * 0.048))));
        FontMetrics fm = g.getFontMetrics();
        String text = "SARI-SARI STORE";
        g.drawString(text, sx + (sw - fm.stringWidth(text)) / 2, sy + (sh + fm.getAscent() - fm.getDescent()) / 2);

        // Counter window with shelves and products
        int wx = bx + (int) (bw * 0.06), wy = by + (int) (h * 0.16), ww = (int) (bw * 0.62), wh = (int) (h * 0.19);
        g.setColor(new Color(62, 42, 32));
        g.fillRect(wx, wy, ww, wh);
        Color[] cols = {new Color(230, 70, 60), new Color(250, 200, 50), new Color(60, 150, 220),
                new Color(80, 180, 100), new Color(240, 130, 40), new Color(170, 90, 200)};
        for (int row = 0; row < 2; row++) {
            int shelfY = wy + (int) (wh * (row + 1) / 2.0) - 3;
            int boxH = (int) (wh * 0.32);
            for (int i = 0; i < 12; i++) {
                int bwid = Math.max(8, ww / 14);
                g.setColor(cols[(i + row * 2) % cols.length]);
                g.fillRect(wx + 8 + i * (ww - 16) / 12, shelfY - boxH, bwid, boxH);
            }
            g.setColor(new Color(150, 100, 60));
            g.fillRect(wx, shelfY, ww, 4);
        }
        g.setColor(new Color(35, 35, 35));                       // window grill
        for (int x = wx + ww / 8; x < wx + ww; x += ww / 8) g.drawLine(x, wy, x, wy + wh);

        // Striped awning
        int ax = wx - (int) (w * 0.02), ay = wy - (int) (h * 0.055), aw = ww + (int) (w * 0.04), ah = (int) (h * 0.055);
        int stripes = 10, stw = aw / stripes;
        for (int i = 0; i < stripes; i++) {
            g.setColor(i % 2 == 0 ? new Color(210, 40, 40) : Color.WHITE);
            g.fillRect(ax + i * stw, ay, stw, ah);
            g.fillOval(ax + i * stw, ay + ah - stw / 2, stw, stw);
        }

        // Hanging sachets under the awning
        g.setColor(new Color(60, 60, 60));
        for (int i = 0; i < 9; i++) {
            int px = wx + 12 + i * (ww - 30) / 9;
            g.drawLine(px + 4, ay + ah, px + 4, ay + ah + (int) (h * 0.025));
            g.setColor(cols[(i * 5) % cols.length]);
            g.fillRect(px, ay + ah + (int) (h * 0.025), 9, (int) (h * 0.03));
            g.setColor(new Color(60, 60, 60));
        }

        // Counter ledge
        g.setColor(new Color(150, 95, 50));
        g.fillRect(wx - (int) (w * 0.015), wy + wh, ww + (int) (w * 0.03), (int) (h * 0.03));

        // Door
        int dx = bx + (int) (bw * 0.77), dy = by + (int) (h * 0.17), dw = (int) (bw * 0.15), dh = by + bh - dy;
        g.setColor(new Color(120, 75, 40));
        g.fillRect(dx, dy, dw, dh);
        g.setColor(new Color(90, 55, 28));
        g.drawRect(dx + dw / 6, dy + dh / 10, dw * 2 / 3, dh * 2 / 5);
        g.setColor(new Color(250, 210, 80));
        g.fillOval(dx + dw - dw / 5, dy + dh / 2, 8, 8);

        // Crate of bottles in front
        int cx = bx + (int) (bw * 0.05), cy = by + bh - (int) (h * 0.05), cw = (int) (w * 0.12), ch = (int) (h * 0.05);
        g.setColor(new Color(215, 40, 40));
        g.fillRect(cx, cy, cw, ch);
        g.setColor(new Color(90, 50, 30));
        for (int i = 0; i < 4; i++) g.fillRect(cx + 5 + i * (cw - 10) / 4, cy - (int) (h * 0.035), 7, (int) (h * 0.035));

        // Caption
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.ITALIC, Math.max(10, w / 32)));
        fm = g.getFontMetrics();
        String cap = "Your neighborhood tindahan";
        g.drawString(cap, (w - fm.stringWidth(cap)) / 2, (int) (h * 0.965));
        g.dispose();
    }

    private void cloud(Graphics2D g, double x, double y, double size) {
        g.fillOval((int) x, (int) (y + size * 0.15), (int) (size * 0.5), (int) (size * 0.35));
        g.fillOval((int) (x + size * 0.25), (int) y, (int) (size * 0.5), (int) (size * 0.45));
        g.fillOval((int) (x + size * 0.5), (int) (y + size * 0.15), (int) (size * 0.5), (int) (size * 0.35));
    }
}

// ==========================================================================
//  Login screen
// ==========================================================================
class StoreLoginFrame extends JFrame {
    private final JTextField txtUser = new JTextField(16);
    private final JPasswordField txtPass = new JPasswordField(16);
    private final JLabel lblMsg = new JLabel(" ", SwingConstants.CENTER);
    private final JButton btnLogin = new JButton("Log in");
    private int failedAttempts = 0;

    static void open() {
        StoreUserStore.ensureDefaults();
        new StoreLoginFrame().setVisible(true);
    }

    private StoreLoginFrame() {
        setTitle("Christian Sari-Sari Store - Login");
        setSize(860, 480);
        setResizable(false);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new GridLayout(1, 2));

        add(new StorePicturePanel());                           // left side: picture

        // Right side: form
        JPanel right = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new GridLayout(2, 1));
        head.setBackground(SariSariStore.GREEN);
        head.setBorder(new EmptyBorder(16, 10, 16, 10));
        JLabel t1 = new JLabel("Sari-Sari Store", SwingConstants.CENTER);
        t1.setForeground(Color.WHITE);
        t1.setFont(t1.getFont().deriveFont(Font.BOLD, 22f));
        JLabel t2 = new JLabel("Inventory System - please log in", SwingConstants.CENTER);
        t2.setForeground(new Color(215, 240, 224));
        head.add(t1);
        head.add(t2);
        right.add(head, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(18, 30, 10, 30));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 4, 5, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0;
        g.weightx = 1;

        g.gridy = 0;
        form.add(new JLabel("Username"), g);
        g.gridy = 1;
        form.add(txtUser, g);
        g.gridy = 2;
        form.add(new JLabel("Password"), g);
        g.gridy = 3;
        form.add(txtPass, g);

        JCheckBox chkShow = new JCheckBox("Show password");
        char defaultEcho = txtPass.getEchoChar();
        chkShow.addActionListener(e -> txtPass.setEchoChar(chkShow.isSelected() ? (char) 0 : defaultEcho));
        g.gridy = 4;
        form.add(chkShow, g);

        btnLogin.setBackground(SariSariStore.GREEN);
        btnLogin.setForeground(Color.WHITE);
        btnLogin.setOpaque(true);
        btnLogin.setBorderPainted(false);
        btnLogin.setFocusPainted(false);
        btnLogin.setFont(btnLogin.getFont().deriveFont(Font.BOLD, 14f));
        btnLogin.setPreferredSize(new Dimension(100, 36));
        btnLogin.addActionListener(e -> doLogin());
        g.gridy = 5;
        form.add(btnLogin, g);

        JButton btnCreate = new JButton("Create account");
        btnCreate.addActionListener(e -> showRegisterDialog());
        g.gridy = 6;
        form.add(btnCreate, g);

        lblMsg.setForeground(SariSariStore.RED);
        g.gridy = 7;
        form.add(lblMsg, g);

        right.add(form, BorderLayout.CENTER);
        add(right);
        getRootPane().setDefaultButton(btnLogin);               // Enter key = log in
    }

    private void doLogin() {
        String user = txtUser.getText().trim();
        String pass = new String(txtPass.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            lblMsg.setText("Please enter your username and password.");
            return;
        }
        String[] result = StoreUserStore.authenticate(user, pass);
        if (result == null) {
            failedAttempts++;
            txtPass.setText("");
            if (failedAttempts >= 3) {
                lockForAWhile();
            } else {
                lblMsg.setText("Wrong username or password. Attempts left: " + (3 - failedAttempts));
            }
            return;
        }
        dispose();
        new SariSariStore(result[0], result[1]).setVisible(true);
    }

    /** After 3 wrong attempts the Log in button is disabled for 10 seconds. */
    private void lockForAWhile() {
        btnLogin.setEnabled(false);
        lblMsg.setText("Too many failed attempts. Try again in 10 seconds.");
        Timer timer = new Timer(10000, e -> {
            btnLogin.setEnabled(true);
            failedAttempts = 0;
            lblMsg.setText(" ");
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void showRegisterDialog() {
        JTextField tUser = new JTextField(15);
        JPasswordField tPass = new JPasswordField(15);
        JPasswordField tConfirm = new JPasswordField(15);
        JPanel p = new JPanel(new GridLayout(0, 2, 8, 8));
        p.add(new JLabel("Username:"));
        p.add(tUser);
        p.add(new JLabel("Password:"));
        p.add(tPass);
        p.add(new JLabel("Confirm password:"));
        p.add(tConfirm);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, "Create account (Staff)",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            String error = StoreUserStore.register(tUser.getText().trim(),
                    new String(tPass.getPassword()), new String(tConfirm.getPassword()));
            if (error == null) {
                txtUser.setText(tUser.getText().trim());
                txtPass.setText("");
                lblMsg.setText(" ");
                JOptionPane.showMessageDialog(this, "Account created. You can now log in.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(this, error, "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
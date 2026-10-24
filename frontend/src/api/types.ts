export type Role = 'EMPLOYEE' | 'ADMIN';
export type Category = 'CHINESE' | 'WESTERN' | 'JAPANESE' | 'LIGHT_MEAL' | 'KOREAN' | 'SOUTHEAST_ASIAN';
export type SpiceLevel = 'NONE' | 'MILD' | 'MEDIUM' | 'HOT';
export type Allergen = 'PEANUTS' | 'DAIRY' | 'EGG' | 'GLUTEN' | 'SOY' | 'FISH' | 'SHELLFISH';
export type TasteIntensity = 'LIGHT' | 'BALANCED' | 'RICH';
export type MealSlot = 'LUNCH' | 'DINNER';
export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED';
// Runtime parsing enforces the contract's two-decimal string before arithmetic.
export type Money = string;

export interface User {
  id: number;
  email: string;
  displayName: string;
  role: Role;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: 900;
  user: User;
}

export interface LoginRequest { email: string; password: string }
export interface RegisterRequest extends LoginRequest { displayName: string }

export interface ProblemDetails {
  type?: string;
  title?: string;
  status: number;
  detail: string;
  code: string;
  correlationId?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
  details?: Record<string, unknown>;
}

export interface PageMeta {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface OptionValue {
  id: number;
  name: string;
  priceDelta: Money;
}

export interface OptionGroup {
  id: number;
  name: string;
  selectionType: 'SINGLE' | 'MULTIPLE';
  required: boolean;
  minSelections: number;
  maxSelections: number;
  values: OptionValue[];
}

export interface DishCard {
  menuItemId: number;
  dishId: number;
  name: string;
  description: string;
  imageUrl: string;
  basePrice: Money;
  category: Category;
  allergens: Allergen[];
  spiceLevel: SpiceLevel;
  remainingStock: number;
  stockVersion: number;
  lowStock: boolean;
  soldOut: boolean;
  preferenceMatches: string[];
}

export interface DishDetail extends DishCard {
  proteinSource: string;
  optionGroups: OptionGroup[];
}

export interface MenuPage extends PageMeta {
  date: string;
  items: DishCard[];
}

export interface Preference {
  allergens: Allergen[];
  preferredCategories: Category[];
  spiceLevel: SpiceLevel | null;
  tasteIntensity: TasteIntensity | null;
  budgetMin: Money | null;
  budgetMax: Money | null;
}

export interface Address {
  id: number;
  label: string;
  addressText: string;
  isDefault: boolean;
}

export interface AddressWrite {
  label: string;
  addressText: string;
  isDefault: boolean;
}

export interface OrderLineRequest {
  dishId: number;
  quantity: number;
  optionValueIds: number[];
}

export interface OrderRequest {
  deliveryDate: string;
  mealSlot: MealSlot;
  addressId: number | null;
  newAddress: (AddressWrite & { save: boolean }) | null;
  acceptedResolvedDeliveryDate: string | null;
  acceptedResolvedMealSlot: MealSlot | null;
  items: OrderLineRequest[];
}

export interface OrderQuote {
  resolvedDeliveryDate: string;
  resolvedMealSlot: MealSlot;
  slotAdjusted: boolean;
  lines: Array<{
    dishId: number;
    dishName: string;
    quantity: number;
    optionValueIds: number[];
    unitPrice: Money;
    subtotal: Money;
  }>;
  total: Money;
  warnings: Array<'BUDGET_EXCEEDED' | 'SLOT_ADJUSTED'>;
  unavailableItems: Array<{ dishId: number; name: string; requested: number; remaining: number }>;
  existingOrder: { id: number; orderNumber: string; status: OrderStatus } | null;
}

export interface Order {
  id: number;
  orderNumber: string;
  userId: number;
  userDisplayName: string;
  deliveryDate: string;
  mealSlot: MealSlot;
  deliveryAddress: string;
  status: OrderStatus;
  total: Money;
  version: number;
  items: Array<{
    id: number;
    dishId: number;
    dishName: string;
    basePrice: Money;
    unitPrice: Money;
    quantity: number;
    subtotal: Money;
    options: Array<{ groupName: string; valueName: string; priceDelta: Money }>;
  }>;
  statusHistory: Array<{
    fromStatus: OrderStatus | null;
    toStatus: OrderStatus;
    actorRole: Role;
    actorDisplayName: string;
    reason: string | null;
    createdAt: string;
  }>;
  createdAt: string;
}

export interface OrderPage extends PageMeta { items: Order[] }

export interface OrderSubmission {
  submissionId: string;
  state: 'PROCESSING' | 'COMPLETED' | 'FAILED';
  order?: Order | null;
  failure?: ProblemDetails | null;
  statusUrl?: string;
}

export interface DishWrite {
  name: string;
  description: string;
  basePrice: Money;
  category: Category;
  proteinSource: string;
  spiceLevel: SpiceLevel;
  allergens: Allergen[];
  imageUrl: string;
  published: boolean;
  version: number;
  optionGroups: Array<{
    id: number | null;
    name: string;
    selectionType: 'SINGLE' | 'MULTIPLE';
    required: boolean;
    minSelections: number;
    maxSelections: number;
    displayOrder: number;
    active: boolean;
    values: Array<{
      id: number | null;
      name: string;
      priceDelta: Money;
      displayOrder: number;
      active: boolean;
    }>;
  }>;
}

export interface AdminDish extends DishWrite { id: number }
export interface AdminDishPage extends PageMeta { items: AdminDish[] }
export interface AdminDailyMenuItem {
  menuItemId: number;
  dishId: number;
  dishName: string;
  initialStock: number;
  remainingStock: number;
  visible: boolean;
  stockVersion: number;
}
export interface DailyMenuItemWrite {
  dishId: number;
  initialStock: number;
  visible: boolean;
}
export interface AdminDailyMenu {
  date: string;
  status: 'DRAFT' | 'PUBLISHED';
  publicationVersion: number;
  items: AdminDailyMenuItem[];
}
export interface OrderTransitionRequest {
  targetStatus: 'CONFIRMED' | 'COMPLETED' | 'CANCELLED';
  expectedVersion: number;
  reason?: string | null;
}
export interface Dashboard {
  asOf: string;
  date: string;
  overview: {
    totalOrders: number;
    revenue: Money;
    completed: number;
    pending: number;
    confirmed: number;
    cancelled: number;
  };
  topDishes: Array<{ dishId: number; name: string; quantity: number }>;
  mealDistribution: Array<{ mealSlot: MealSlot; orders: number }>;
  trend: Array<{ date: string; orders: number; revenue: Money }>;
  lowStock: Array<{ dishId: number; name: string; remainingStock: number }>;
}
